package id.art.tracker.playground.scenario

import android.graphics.PointF
import android.view.MotionEvent

/** One planned motion event, in raw SCREEN coordinates (window position already added). */
data class TouchSpec(val action: Int, val screenX: Float, val screenY: Float, val downTime: Long, val eventTime: Long)

object SyntheticTouchPlanner {
    const val TAP_MS = 60L

    fun tap(centerInWindow: PointF, windowOriginOnScreen: PointF, startMs: Long): List<TouchSpec> {
        val x = centerInWindow.x + windowOriginOnScreen.x
        val y = centerInWindow.y + windowOriginOnScreen.y
        return listOf(
            TouchSpec(MotionEvent.ACTION_DOWN, x, y, startMs, startMs),
            TouchSpec(MotionEvent.ACTION_UP, x, y, startMs, startMs + TAP_MS),
        )
    }

    /** DOWN, MOVEs every [stepMs] (strictly before the UP), UP at `startMs + durationMs` exactly on [to]. */
    fun swipe(
        from: PointF,
        to: PointF,
        windowOriginOnScreen: PointF,
        startMs: Long,
        durationMs: Long = 300,
        stepMs: Long = 16,
    ): List<TouchSpec> {
        val ox = windowOriginOnScreen.x
        val oy = windowOriginOnScreen.y
        val specs = ArrayList<TouchSpec>()
        specs += TouchSpec(MotionEvent.ACTION_DOWN, from.x + ox, from.y + oy, startMs, startMs)
        val moves = ((durationMs - 1) / stepMs).coerceAtLeast(0)
        for (k in 1..moves) {
            val fraction = (k * stepMs).toFloat() / durationMs
            specs += TouchSpec(
                MotionEvent.ACTION_MOVE,
                from.x + (to.x - from.x) * fraction + ox,
                from.y + (to.y - from.y) * fraction + oy,
                startMs,
                startMs + k * stepMs,
            )
        }
        specs += TouchSpec(MotionEvent.ACTION_UP, to.x + ox, to.y + oy, startMs, startMs + durationMs)
        return specs
    }
}
