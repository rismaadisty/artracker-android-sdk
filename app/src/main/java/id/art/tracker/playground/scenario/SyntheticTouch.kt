package id.art.tracker.playground.scenario

import android.app.Activity
import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Dispatches planned touches through `activity.window.decorView.dispatchTouchEvent`, the framework's own entry into
 * `Window.Callback`. While replay records, that callback is the SDK's `TouchRecorder`, so these touches are recorded
 * exactly like real ones; the delegate then delivers them to the view tree (a real click happens).
 *
 * Each event carries raw SCREEN coordinates (`rawX`/`rawY`, which the SDK divides by the density) and window-relative
 * `x`/`y` (via `offsetLocation`), like a framework-delivered event, and `SOURCE_TOUCHSCREEN`.
 */
object SyntheticTouch {
    suspend fun tap(
        activity: Activity,
        centerInWindow: PointF,
        windowOrigin: PointF = originOf(activity),
        pause: suspend (Long) -> Unit = { delay(it) },
    ) = dispatch(activity, SyntheticTouchPlanner.tap(centerInWindow, windowOrigin, SystemClock.uptimeMillis()), windowOrigin, pause)

    suspend fun swipe(
        activity: Activity,
        from: PointF,
        to: PointF,
        windowOrigin: PointF = originOf(activity),
        pause: suspend (Long) -> Unit = { delay(it) },
    ) = dispatch(activity, SyntheticTouchPlanner.swipe(from, to, windowOrigin, SystemClock.uptimeMillis()), windowOrigin, pause)

    /**
     * Advisory only: true when the window's callback is the SDK's `TouchRecorder` (compared by class name, so it does not
     * survive R8 renaming; never use it to gate behaviour).
     */
    fun isRecorderInstalled(activity: Activity): Boolean =
        runCatching { activity.window.callback?.javaClass?.simpleName == "TouchRecorder" }.getOrDefault(false)

    fun originOf(activity: Activity): PointF {
        val location = IntArray(2)
        activity.window.decorView.getLocationOnScreen(location)
        return PointF(location[0].toFloat(), location[1].toFloat())
    }

    private suspend fun dispatch(activity: Activity, specs: List<TouchSpec>, origin: PointF, pause: suspend (Long) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            val decor = activity.window.decorView
            specs.forEachIndexed { index, spec ->
                if (index > 0) pause(spec.eventTime - specs[index - 1].eventTime)
                val event = MotionEvent.obtain(spec.downTime, spec.eventTime, spec.action, spec.screenX, spec.screenY, 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    event.offsetLocation(-origin.x, -origin.y)
                    decor.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
    }
}
