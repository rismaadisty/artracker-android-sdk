package id.art.tracker.replay.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.view.View
import android.view.Window
import id.art.tracker.core.SdkLog
import kotlin.math.roundToInt

/**
 * The main-thread half of a screenshot: composites every root window into one screen-sized bitmap and collects what
 * must be masked, in the same pass. Nothing is compressed or sent here, and the bitmap is still unmasked
 * ([ScreenshotEncoder] paints the masks on the processing thread, before scaling).
 *
 * - The base window (the Activity's, the first root) is copied with `PixelCopy` on API 26+ (so `SurfaceView`s and
 *   hardware layers come out right); on API 24-25, or when the copy fails, it is drawn with `View.draw`. Dialogs and
 *   popups are always drawn with `View.draw`, on top, after the copy finished, with the dim their window applies.
 * - A `FLAG_SECURE` window is never copied or drawn (no fallback either, it would bypass the app's protection): its
 *   area is listed as a mask. The keyboard (another process) cannot be captured: its band is returned as [RawFrame.keyboard].
 * - A mask collection that fails drops the frame; so does a busy pool (both buffers in use).
 *
 * Not thread-safe: main thread only; [onResult] runs on the main thread, or on the `PixelCopy` callback thread when
 * the window was copied (it always hops back to main before touching views).
 */
internal class ScreenshotCapturer(
    private val pool: BitmapPool,
    private val maskCollector: MaskCollector,
    private val mainHandler: Handler,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val pixelCopy: PixelCopyApi,
    private val imeHeight: (View) -> Int = WindowFacts::imeHeightPx,
    private val decorOf: (Window) -> View = { it.decorView },
    private val isSecure: (View) -> Boolean = WindowFacts::isSecure,
    private val dimAmount: (View) -> Float = WindowFacts::dimAmount,
) {
    private var capturing = false
    private val loc = IntArray(2)

    /** [roots] in z-order (last = top); [baseWindow] is the Activity's window, whose decor should be `roots[0]`. */
    fun capture(roots: List<View>, baseWindow: Window?, ts: Long, onResult: (RawFrame?) -> Unit) {
        if (capturing || roots.isEmpty()) {
            onResult(null)
            return
        }
        val rects = ArrayList<MaskRect>(roots.size)
        val masks = ArrayList<MaskRect>()
        var secure = 0
        var width = 0
        var height = 0
        try {
            for (root in roots) {
                root.getLocationOnScreen(loc)
                val rect = MaskRect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
                rects.add(rect)
                width = maxOf(width, rect.right)
                height = maxOf(height, rect.bottom)
                if (isSecure(root)) {
                    masks.add(rect)
                    secure++
                } else if (!maskCollector.collect(root, masks)) {
                    onResult(null) // an unmasked frame is never sent
                    return
                }
            }
        } catch (t: Throwable) {
            SdkLog.w("screenshot mask collection failed", t)
            onResult(null)
            return
        }
        val bitmap = pool.acquire(width, height)
        if (bitmap == null) {
            onResult(null)
            return
        }
        val keyboard = try {
            WindowFacts.imeRect(width, height, imeHeight(roots[0]))
        } catch (_: Throwable) {
            null
        }
        capturing = true
        val finish = {
            capturing = false
            onResult(RawFrame(bitmap, ts, masks, secure, keyboard) { pool.release(bitmap) })
        }
        val fail = { t: Throwable ->
            capturing = false
            SdkLog.w("screenshot capture failed", t)
            pool.release(bitmap)
            onResult(null)
        }
        try {
            val base = roots[0]
            val baseSecure = isSecure(base)
            val copyBase = !baseSecure && sdkInt >= 26 && baseWindow != null && decorOf(baseWindow) === base
            if (copyBase) {
                copyWindow(baseWindow!!, base, rects[0], bitmap, masks) {
                    // back on main: the overlays are views
                    mainHandler.post {
                        try {
                            drawOverlays(roots, rects, bitmap, masks)
                            finish()
                        } catch (t: Throwable) {
                            fail(t)
                        }
                    }
                }
            } else {
                val canvas = Canvas(bitmap)
                if (!baseSecure) drawView(canvas, base, rects[0], masks)
                drawOverlays(roots, rects, bitmap, masks)
                finish()
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun copyWindow(window: Window, base: View, rect: MaskRect, bitmap: Bitmap, masks: MutableList<MaskRect>, then: () -> Unit) {
        val exact = rect.left == 0 && rect.top == 0 && bitmap.width == base.width && bitmap.height == base.height
        val target = if (exact) bitmap else Bitmap.createBitmap(base.width.coerceAtLeast(1), base.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        pixelCopy.copy(window, target) { status ->
            mainHandler.post {
                try {
                    if (status != PixelCopyApi.SUCCESS) {
                        // The copy failed: the same window, drawn by its views (never for a secure window, checked above).
                        drawView(Canvas(bitmap), base, rect, masks)
                    } else if (!exact) {
                        Canvas(bitmap).drawBitmap(target, rect.left.toFloat(), rect.top.toFloat(), null)
                    }
                } finally {
                    if (!exact) target.recycle()
                }
                then()
            }
        }
    }

    private fun drawOverlays(roots: List<View>, rects: List<MaskRect>, bitmap: Bitmap, masks: MutableList<MaskRect>) {
        val canvas = Canvas(bitmap)
        for (i in 1 until roots.size) {
            val root = roots[i]
            if (isSecure(root)) continue
            val dim = dimAmount(root)
            if (dim > 0f) canvas.drawColor(Color.argb((dim * 255).roundToInt(), 0, 0, 0))
            drawView(canvas, root, rects[i], masks)
        }
    }

    /** `View.draw` at the view's screen position; a view that throws while drawing is masked whole. */
    private fun drawView(canvas: Canvas, view: View, rect: MaskRect, masks: MutableList<MaskRect>) {
        try {
            canvas.save()
            canvas.translate(rect.left.toFloat(), rect.top.toFloat())
            view.draw(canvas)
        } catch (t: Throwable) {
            SdkLog.w("drawing a window for the screenshot failed", t)
            masks.add(rect)
        } finally {
            canvas.restore()
        }
    }
}
