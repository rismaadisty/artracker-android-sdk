package id.art.tracker.replay.capture

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap

/**
 * Turns small ICONS into `data:image/...;base64,` URIs so the web player can draw them like the app does.
 *
 * What counts as an icon (privacy: no photo may leave the device through here):
 * - a vector drawable (platform or AndroidX compat) or a bitmap of at most [MAX_ICON_DP] x [MAX_ICON_DP] px,
 *   shown in a box of at most [MAX_ICON_DP] dp. Anything else (photos, thumbnails, big images) keeps the grey
 *   placeholder; callers never ask for masked nodes either.
 *
 * Cost control (main thread): results are cached per source object ([WeakHashMap], so nothing is kept alive) and per
 * variant (size, tint, drawable state), so an icon is drawn and encoded once; at most [frameBudget] NEW rasterizations
 * happen per frame ([beginFrame]), the rest appear on the next frames. A failure, an oversized result or an encoder
 * exception is remembered too and never retried. Drawing happens on a private copy of the drawable: the live one's
 * bounds and state are never touched. Never throws.
 */
internal class IconRasterizer(
    private val encode: (Bitmap) -> Encoded? = ::encodeSmall,
    private val frameBudget: Int = DEFAULT_FRAME_BUDGET,
) {
    class Encoded(val mime: String, val bytes: ByteArray)

    private var spent = 0
    private val cache = WeakHashMap<Any, HashMap<String, String>>()

    fun beginFrame() {
        spent = 0
        if (cache.size > MAX_CACHED_SOURCES) cache.clear()
    }

    /** Icon URI for [drawable] shown at [widthDp] x [heightDp], or null (not an icon, over budget, failed). */
    fun forDrawable(
        drawable: Drawable,
        tint: ColorStateList?,
        state: IntArray,
        widthDp: Int,
        heightDp: Int,
    ): String? {
        if (widthDp !in 1..MAX_ICON_DP || heightDp !in 1..MAX_ICON_DP) return null
        val source = try {
            unwrap(drawable).takeIf(::isIconSource)
        } catch (_: Throwable) {
            null
        } ?: return null
        val shared = try {
            source.constantState
        } catch (_: Throwable) {
            null
        } ?: return null
        val tintKey = tint?.getColorForState(state, tint.defaultColor)
        val variant = "$widthDp x $heightDp|$tintKey|${state.contentHashCode()}"
        return render(shared, variant, widthDp, heightDp) { canvas ->
            val copy = shared.newDrawable().mutate()
            if (tint != null) copy.setTintList(tint)
            copy.state = state
            copy.setBounds(0, 0, widthDp, heightDp)
            copy.draw(canvas)
        }
    }

    /** Core: cache lookup, budget, draw into a [widthPx] x [heightPx] bitmap, encode, wrap as a data URI. */
    fun render(key: Any, variant: String, widthPx: Int, heightPx: Int, draw: (Canvas) -> Unit): String? {
        if (widthPx !in 1..MAX_ICON_DP || heightPx !in 1..MAX_ICON_DP) return null
        val perKey = cache.getOrPut(key) { HashMap() }
        perKey[variant]?.let { return it.ifEmpty { null } }
        if (spent >= frameBudget) return null
        spent++
        val uri = try {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            try {
                draw(Canvas(bitmap))
                encode(bitmap)?.takeIf { it.bytes.isNotEmpty() && it.bytes.size <= MAX_BYTES }
                    ?.let { "data:image/${it.mime};base64," + Base64.encodeToString(it.bytes, Base64.NO_WRAP) }
            } finally {
                bitmap.recycle()
            }
        } catch (_: Throwable) {
            null
        }
        perKey[variant] = uri ?: ""
        return uri
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

    private fun isIconSource(d: Drawable): Boolean = when {
        d is VectorDrawable || d is AnimatedVectorDrawable -> true
        d.javaClass.name in COMPAT_VECTORS -> true
        d is BitmapDrawable -> d.bitmap?.let { !it.isRecycled && it.width <= MAX_ICON_DP && it.height <= MAX_ICON_DP } == true
        else -> false
    }

    companion object {
        /** Icons are at most this many dp (and, for bitmaps, px) per side; also the raster size cap at 1x. */
        const val MAX_ICON_DP = 64
        const val MAX_BYTES = 6 * 1024
        const val DEFAULT_FRAME_BUDGET = 24
        private const val MAX_UNWRAP = 4
        private const val MAX_CACHED_SOURCES = 512
        private val COMPAT_VECTORS = setOf(
            "androidx.vectordrawable.graphics.drawable.VectorDrawableCompat",
            "androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat",
        )

        /** One instance for the whole process: walkers share the cache and reset the budget per frame. */
        val shared: IconRasterizer by lazy { IconRasterizer() }

        /** Lossless WebP on API 30+, PNG before: both keep transparency, a 24 px icon is a few hundred bytes. */
        fun encodeSmall(bitmap: Bitmap): Encoded? {
            val out = ByteArrayOutputStream(512)
            val lossless = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val format = if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
            return if (bitmap.compress(format, 100, out)) Encoded(if (lossless) "webp" else "png", out.toByteArray()) else null
        }
    }
}
