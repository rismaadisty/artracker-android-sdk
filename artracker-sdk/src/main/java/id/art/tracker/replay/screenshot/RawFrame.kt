package id.art.tracker.replay.screenshot

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A composited, still UNMASKED screen (screen pixels) with everything the processing side needs to mask it. The
 * [bitmap] belongs to a pool: whoever is done with the frame must call [release] (once; extra calls do nothing).
 * It never leaves the device unmasked: [ScreenshotEncoder] paints [masks] and [keyboard] before anything else.
 */
internal class RawFrame(
    val bitmap: Bitmap,
    val ts: Long,
    /** Regions to paint over, in bitmap pixels; includes the area of every `FLAG_SECURE` window. */
    val masks: List<MaskRect>,
    /** How many of the [masks] are whole `FLAG_SECURE` windows. */
    val secureCount: Int,
    /** The area the keyboard covers (not capturable), painted as a neutral placeholder; null when it is hidden. */
    val keyboard: MaskRect?,
    private val onRelease: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}
