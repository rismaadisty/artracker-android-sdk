package id.art.tracker.playground.scenario

import android.app.Application
import android.graphics.PointF
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric only for a real `PointF` (the plain android.jar stub has no fields). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyntheticTouchPlannerTest {
    @Test
    fun aTapIsADownThenAnUpAtTheSameScreenPoint() {
        val specs = SyntheticTouchPlanner.tap(PointF(100f, 200f), PointF(0f, 48f), startMs = 1_000)
        assertEquals(2, specs.size)
        assertEquals(MotionEvent.ACTION_DOWN, specs[0].action)
        assertEquals(MotionEvent.ACTION_UP, specs[1].action)
        specs.forEach {
            assertEquals(100f, it.screenX, 0f)
            assertEquals(248f, it.screenY, 0f)
            assertEquals(1_000L, it.downTime)
        }
        assertEquals(1_000L, specs[0].eventTime)
        assertEquals(1_060L, specs[1].eventTime)
    }

    @Test
    fun theWindowOriginIsAddedToBothAxes() {
        val spec = SyntheticTouchPlanner.tap(PointF(10f, 20f), PointF(5f, 7f), 0).first()
        assertEquals(15f, spec.screenX, 0f)
        assertEquals(27f, spec.screenY, 0f)
    }

    @Test
    fun aSwipeStartsWithDownEndsWithUpAndMovesInBetween() {
        val specs = SyntheticTouchPlanner.swipe(PointF(10f, 10f), PointF(210f, 10f), PointF(0f, 0f), startMs = 0, durationMs = 300, stepMs = 16)
        assertEquals(MotionEvent.ACTION_DOWN, specs.first().action)
        assertEquals(MotionEvent.ACTION_UP, specs.last().action)
        assertEquals(10f, specs.first().screenX, 0f)
        assertEquals(210f, specs.last().screenX, 0f)
        val middle = specs.drop(1).dropLast(1)
        assertTrue(middle.size >= 5)
        assertTrue(middle.all { it.action == MotionEvent.ACTION_MOVE })
        assertEquals(300L, specs.last().eventTime - specs.first().eventTime)
    }

    @Test
    fun swipeEventTimesStrictlyIncrease() {
        for (duration in listOf(300L, 320L, 48L)) {
            val specs = SyntheticTouchPlanner.swipe(PointF(0f, 0f), PointF(100f, 100f), PointF(0f, 0f), 5, duration, 16)
            assertTrue("duration=$duration", specs.zipWithNext().all { (a, b) -> b.eventTime > a.eventTime })
            assertTrue(specs.all { it.downTime == 5L })
        }
    }

    @Test
    fun moveCoordinatesStayOnTheLineFromStartToEnd() {
        val specs = SyntheticTouchPlanner.swipe(PointF(0f, 0f), PointF(160f, 80f), PointF(0f, 0f), 0, 320, 16)
        specs.forEach { assertEquals(it.screenX / 2f, it.screenY, 0.001f) }
    }
}
