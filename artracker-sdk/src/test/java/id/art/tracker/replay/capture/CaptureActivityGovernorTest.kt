package id.art.tracker.replay.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureActivityGovernorTest {
    private fun governor(fps: Double = 1.0) = CaptureActivityGovernor(fps).also { it.onStart(0) }

    @Test
    fun startsActive() {
        val g = governor(2.0)
        assertEquals(CaptureState.ACTIVE, g.state)
        assertEquals(2.0, g.periodicFps()!!, 0.0)
        assertTrue(g.keyframeAllowed)
        assertEquals(30_000L, g.nextTransitionAtMs())
    }

    @Test
    fun idleAfter30s() {
        val g = governor()
        assertEquals(CaptureState.ACTIVE, g.evaluate(29_999))
        assertEquals(CaptureState.IDLE, g.evaluate(30_000))
        assertEquals(0.2, g.periodicFps()!!, 0.0)
        assertFalse(g.keyframeAllowed)
        assertEquals(180_000L, g.nextTransitionAtMs())
    }

    @Test
    fun dormantAfter180s() {
        val g = governor()
        assertEquals(CaptureState.IDLE, g.evaluate(179_999))
        assertEquals(CaptureState.DORMANT, g.evaluate(180_000))
        assertNull(g.periodicFps())
        assertNull(g.nextTransitionAtMs())
        assertFalse(g.keyframeAllowed)
    }

    @Test
    fun dormantReachedDirectlyWhenEvaluatedLate() {
        val g = governor()
        assertEquals(CaptureState.DORMANT, g.evaluate(1_000_000))
    }

    @Test
    fun inputWakes() {
        val g = governor()
        g.evaluate(180_000)
        assertTrue(g.onUserInput(200_000))
        assertEquals(CaptureState.ACTIVE, g.state)
        assertFalse(g.onUserInput(200_100))
        assertEquals(230_100L, g.nextTransitionAtMs())
    }

    @Test
    fun inputWakesFromIdle_evenWithoutPriorEvaluate() {
        val g = governor()
        assertTrue(g.onUserInput(45_000)) // 45 s without input: it was IDLE although nobody called evaluate()
        assertFalse(g.onUserInput(45_001))
    }

    @Test
    fun inputInActive_extendsTheDeadline() {
        val g = governor()
        assertFalse(g.onUserInput(20_000))
        assertEquals(CaptureState.ACTIVE, g.evaluate(49_999))
        assertEquals(CaptureState.IDLE, g.evaluate(50_000))
    }

    @Test
    fun triggerDebounce_perKind_inEveryState_andNeverChangesState() {
        for (at in longArrayOf(0, 40_000, 200_000)) {
            val g = governor()
            val before = g.evaluate(at)
            assertEquals(300L, g.admitTrigger(TriggerKind.SCREEN, at))
            assertEquals(300L, g.admitTrigger(TriggerKind.WINDOW, at))
            assertEquals(300L, g.admitTrigger(TriggerKind.ERROR, at))
            assertEquals(300L, g.admitTrigger(TriggerKind.KEYBOARD, at))
            assertEquals(500L, g.admitTrigger(TriggerKind.NETWORK, at))
            assertEquals(0L, g.admitTrigger(TriggerKind.MANUAL, at))
            assertEquals(before, g.state)
        }
    }

    @Test
    fun networkRateLimit_dormant_oncePer10s() {
        val g = governor()
        g.evaluate(180_000)
        val t = 180_000L
        assertEquals(500L, g.admitTrigger(TriggerKind.NETWORK, t))
        assertNull(g.admitTrigger(TriggerKind.NETWORK, t + 3_000))
        assertNull(g.admitTrigger(TriggerKind.NETWORK, t + 6_000))
        assertNull(g.admitTrigger(TriggerKind.NETWORK, t + 9_000))
        assertEquals(500L, g.admitTrigger(TriggerKind.NETWORK, t + 10_000))
    }

    @Test
    fun networkRateLimit_idle_oncePer5s() {
        val g = governor()
        assertEquals(500L, g.admitTrigger(TriggerKind.NETWORK, 30_000))
        assertNull(g.admitTrigger(TriggerKind.NETWORK, 34_999))
        assertEquals(500L, g.admitTrigger(TriggerKind.NETWORK, 35_000))
    }

    @Test
    fun networkRateLimit_active_neverLimited() {
        val g = governor()
        repeat(5) { assertEquals(500L, g.admitTrigger(TriggerKind.NETWORK, it * 10L)) }
    }

    @Test
    fun rateLimit_doesNotApplyToOtherKinds() {
        val g = governor()
        g.evaluate(180_000)
        assertEquals(500L, g.admitTrigger(TriggerKind.NETWORK, 180_000))
        assertEquals(300L, g.admitTrigger(TriggerKind.ERROR, 180_001))
        assertEquals(0L, g.admitTrigger(TriggerKind.MANUAL, 180_002))
    }

    @Test
    fun customThresholds() {
        val g = CaptureActivityGovernor(1.0, 5_000, 6_000)
        g.onStart(0)
        assertEquals(CaptureState.ACTIVE, g.evaluate(4_999))
        assertEquals(CaptureState.IDLE, g.evaluate(5_000))
        assertEquals(CaptureState.DORMANT, g.evaluate(6_000))
    }
}
