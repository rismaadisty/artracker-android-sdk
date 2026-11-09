package id.art.tracker.replay.capture

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * Turns small ICONS into `data:image/...;base64,` URIs so the web player can draw them like the app does.
 *
 * What counts as an icon (privacy: no photo may leave the device through here): a VECTOR drawable (platform or AndroidX
 * compat) whose intrinsic size, or the box it is shown in when it has none, is at most [MAX_ICON_DP] dp per side. A
 * bitmap is never an icon, however small: an image loader hands a 40 dp avatar over as a <= 64 px bitmap, and pixels
 * cannot tell an icon from a face. Photos, thumbnails, big images and anything masked keep the grey placeholder;
 * callers never ask for masked nodes either.
 *
 * The icon is drawn at its own size (not stretched to the view box); the web player places it with CSS.
 *
 * Cost control (main thread): results are cached per source object ([WeakHashMap], nothing is kept alive) and per
 * variant (size, tint, drawable state); on top of that, per CONTENT: list rows each own an equal-looking painter or
 * drawable, and they all share one encode. At most [frameBudget] NEW encodes (and [MAX_DRAWS_PER_FRAME] draws) happen
 * per frame ([beginFrame]); the rest appear on the next frames. A failed or oversized encode is remembered too. Drawing
 * happens on a private copy of the drawable: the live one's bounds and state are never touched. Never throws.
 */
internal class IconRasterizer(
    private val encode: (Bitmap) -> Encoded? = ::encodeSmall,
    private val frameBudget: Int = DEFAULT_FRAME_BUDGET,
    private val isIconSource: (Drawable) -> Boolean = ::defaultIsIconSource,
) {
    class Encoded(val mime: String, val bytes: ByteArray)

    private var draws = 0
    private var encodes = 0
    private val cache = WeakHashMap<Any, HashMap<String, String>>()
    private val byContent = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_CONTENT_ENTRIES
    }

    fun beginFrame() {
        draws = 0
        encodes = 0
        if (cache.size > MAX_CACHED_SOURCES) cache.clear()
    }

    /**
     * Icon URI for [drawable] shown in a [boxWidthDp] x [boxHeightDp] box, or null (not an icon, over budget, failed).
     * [density] converts the drawable's intrinsic pixel size to dp.
     */
    fun forDrawable(
        drawable: Drawable,
        tint: ColorStateList?,
        state: IntArray,
        boxWidthDp: Int,
        boxHeightDp: Int,
        density: Float,
    ): String? {
        val source = try {
            unwrap(drawable).takeIf(isIconSource)
        } catch (_: Throwable) {
            null
        } ?: return null
        val shared = try {
            source.constantState
        } catch (_: Throwable) {
            null
        } ?: return null
        val wDp = source.intrinsicWidth.takeIf { it > 0 }?.let { ceil(it / density).toInt() } ?: boxWidthDp
        val hDp = source.intrinsicHeight.takeIf { it > 0 }?.let { ceil(it / density).toInt() } ?: boxHeightDp
        val tintKey = tint?.getColorForState(state, tint.defaultColor)
        val variant = "$wDp x $hDp|$tintKey|${state.contentHashCode()}"
        return render(shared, variant, wDp, hDp) { canvas ->
            val copy = shared.newDrawable().mutate()
            if (tint != null) copy.setTintList(tint)
            copy.state = state
            copy.setBounds(0, 0, wDp, hDp)
            copy.draw(canvas)
        }
    }

    /** Core: cache lookups, budgets, draw into a [widthPx] x [heightPx] bitmap, encode, wrap as a data URI. */
    fun render(key: Any, variant: String, widthPx: Int, heightPx: Int, draw: (Canvas) -> Unit): String? {
        if (widthPx !in 1..MAX_ICON_DP || heightPx !in 1..MAX_ICON_DP) return null
        val perKey = cache.getOrPut(key) { HashMap() }
        perKey[variant]?.let { return it.ifEmpty { null } }
        if (draws >= MAX_DRAWS_PER_FRAME) return null
        draws++
        return try {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            try {
                draw(Canvas(bitmap))
                val contentKey = contentKey(bitmap)
                val known = byContent[contentKey]
                val uri = if (known != null) {
                    known
                } else {
                    if (encodes >= frameBudget) return null // retried on a later frame, nothing remembered
                    encodes++
                    val encoded = encode(bitmap)?.takeIf { it.bytes.isNotEmpty() && it.bytes.size <= MAX_BYTES }
                    val made = encoded?.let { "data:image/${it.mime};base64," + Base64.encodeToString(it.bytes, Base64.NO_WRAP) }.orEmpty()
                    byContent[contentKey] = made
                    made
                }
                perKey[variant] = uri
                uri.ifEmpty { null }
            } finally {
                bitmap.recycle()
            }
        } catch (_: Throwable) {
            perKey[variant] = ""
            null
        }
    }

    /** Size plus a SHA-256 of the pixels: equal-looking icons from different sources share one encode. */
    private fun contentKey(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteBuffer.allocate(pixels.size * 4).apply { asIntBuffer().put(pixels) }.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "${bitmap.width}x${bitmap.height}:" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun unwrap(d: Drawable): Drawable {
        var cur = d
        repeat(MAX_UNWRAP) {
            cur = when (cur) {
                is DrawableContainer -> (cur as DrawableContainer).current ?: return cur
                is InsetDrawable -> (cur as InsetDrawable).drawable ?: return cur
                else -> return cur
            }
        }
        return cur
    }

    companion object {
        /** Icons are at most this many dp per side; also the raster size cap at 1x. */
        const val MAX_ICON_DP = 64
        const val MAX_BYTES = 6 * 1024
        const val DEFAULT_FRAME_BUDGET = 24
        const val MAX_DRAWS_PER_FRAME = 96
        private const val MAX_UNWRAP = 4
        private const val MAX_CACHED_SOURCES = 512
        private const val MAX_CONTENT_ENTRIES = 256
        private val COMPAT_VECTORS = setOf(
            "androidx.vectordrawable.graphics.drawable.VectorDrawableCompat",
            "androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat",
        )

        /** One instance for the whole process: walkers share the cache and reset the budgets per frame. */
        val shared: IconRasterizer by lazy { IconRasterizer() }

        /** Vector drawables only: see the class doc for why a bitmap is never an icon. */
        fun defaultIsIconSource(d: Drawable): Boolean =
            d is VectorDrawable || d is AnimatedVectorDrawable || d.javaClass.name in COMPAT_VECTORS

        /** Lossless WebP on API 30+, PNG before: both keep transparency, a 24 px icon is a few hundred bytes. */
        fun encodeSmall(bitmap: Bitmap): Encoded? {
            val out = ByteArrayOutputStream(512)
            val lossless = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val format = if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
            return if (bitmap.compress(format, 100, out)) Encoded(if (lossless) "webp" else "png", out.toByteArray()) else null
        }
    }
}
