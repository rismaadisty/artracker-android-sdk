package id.art.tracker.replay.screenshot

import android.graphics.Bitmap
import android.os.Handler
import android.view.PixelCopy
import android.view.Window
import androidx.annotation.RequiresApi

/** `PixelCopy.request(window, ...)`, behind an interface so tests need no real compositor. */
internal fun interface PixelCopyApi {
    /** Copies [window] into [dest]; [onDone] gets [SUCCESS] or another code, on a background thread. Never throws. */
    fun copy(window: Window, dest: Bitmap, onDone: (Int) -> Unit)

    companion object {
        const val SUCCESS: Int = 0
        const val ERROR: Int = -1
    }
}

@RequiresApi(26)
internal class RealPixelCopy(private val callbackHandler: Handler) : PixelCopyApi {
    override fun copy(window: Window, dest: Bitmap, onDone: (Int) -> Unit) {
        try {
            PixelCopy.request(window, dest, { status -> onDone(status) }, callbackHandler)
        } catch (_: Throwable) {
            onDone(PixelCopyApi.ERROR)
        }
    }
}
