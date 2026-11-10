package id.art.tracker.replay.screenshot

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Two screen-size `ARGB_8888` buffers, reused so a capture every few hundred milliseconds does not churn the GC.
 * One is typically being captured while the other is being encoded; [acquire] returns null when both are in use (the
 * capture is skipped: latest frame wins). Thread-safe.
 */
internal class BitmapPool(private val slots: Int = 2, private val maxPixels: Long = MAX_PIXELS) {
    private val free = ArrayList<Bitmap>(slots)
    private var leased = 0

    /** A black, mutable buffer of exactly [width] x [height], or null when none is free or it cannot be allocated. */
    @Synchronized
    fun acquire(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || width.toLong() * height > maxPixels) return null
        if (leased >= slots) return null
        val reusable = free.firstOrNull { it.width == width && it.height == height && !it.isRecycled }
        val bitmap = if (reusable != null) {
            free.remove(reusable)
            reusable
        } else {
            free.forEach { it.recycle() }
            free.clear()
            try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) { // OutOfMemoryError included: replay must never take the app down
                return null
            }
        }
        bitmap.eraseColor(Color.BLACK)
        leased++
        return bitmap
    }

    @Synchronized
    fun release(bitmap: Bitmap) {
        if (leased > 0) leased--
        if (bitmap.isRecycled) return
        if (free.size < slots) free.add(bitmap) else bitmap.recycle()
    }

    private companion object {
        /** About an 8K screen: anything above is not a phone screen. */
        const val MAX_PIXELS = 8192L * 4608
    }
}
