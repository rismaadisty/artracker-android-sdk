package id.art.tracker.replay.capture

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.core.SdkLog
import id.art.tracker.testutil.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameSchedulerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val looper: Looper = Looper.getMainLooper()
    private val handler = Handler(looper)
    private val clock = FakeClock(1_000_000L)
    private var captures = 0
    private var captureCost = 0L
    private var captureAction: () -> Unit = {}

    private fun scheduler(baseFps: Double = 1.0, budgetMs: Long = 8): FrameScheduler =
        FrameScheduler(handler, clock, baseFps, budgetMs) {
            captures++
            clock.advance(captureCost)
            captureAction()
        }

    private fun advance(ms: Long) = shadowOf(looper).idleFor(Duration.ofMillis(ms))

    private fun hasPending(): Boolean = shadowOf(looper).nextScheduledTaskTime != Duration.ZERO

    /** Reads the private listener list; null list means none registered. */
    private fun drawListenerCount(root: View): Int {
        val f = ViewTreeObserver::class.java.getDeclaredField("mOnDrawListeners")
        f.isAccessible = true
        val list = f.get(root.viewTreeObserver) as? java.util.List<*>
        return list?.size ?: 0
    }

    private fun dispatchDraw(root: View) {
        val m = ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnDraw")
        m.isAccessible = true
        m.invoke(root.viewTreeObserver)
    }

    @Test
    fun captureDuration_isLoggedOnlyInDebug() {
        ShadowLog.clear()
        val s = scheduler(baseFps = 1.0)
        s.start()
        s.markDirty()
        advance(1_000)
        assertEquals(1, captures)
        assertTrue(ShadowLog.getLogsForTag("ARTracker").isEmpty())

        SdkLog.enabled = true
        try {
            s.markDirty()
            advance(1_000)
            val logs = ShadowLog.getLogsForTag("ARTracker").map { it.msg }
            assertTrue("capture duration not logged: $logs", logs.any { Regex("replay capture took \\d+ us").containsMatchIn(it) })
        } finally {
            SdkLog.enabled = false
            s.stop()
        }
    }

    @Test
    fun noDirty_noCapture() {
        val s = scheduler()
        s.start()
        advance(10_000)
        assertEquals(0, captures)
        s.stop()
    }

    @Test
    fun dirty_capturesOncePerTick() {
        val s = scheduler(baseFps = 2.0) // 500 ms period
        s.start()
        s.markDirty()
        s.markDirty()
        advance(499)
        assertEquals(0, captures)
        advance(1)
        assertEquals(1, captures)
        advance(500) // dirty cleared -> no capture
        assertEquals(1, captures)
        s.markDirty()
        advance(500)
        assertEquals(2, captures)
        s.stop()
    }

    @Test
    fun markDirty_beforeStart_isIgnoredOrHarmless() {
        val s = scheduler()
        s.markDirty()
        advance(5_000)
        assertEquals(0, captures)
        assertFalse(hasPending())
    }

    @Test
    fun requestSoon_capturesAfter100ms() {
        val s = scheduler(baseFps = 0.5) // tick 2000 ms, so the capture below is the extra one
        s.start()
        s.requestSoon()
        advance(99)
        assertEquals(0, captures)
        advance(1)
        assertEquals(1, captures)
        s.stop()
    }

    @Test
    fun requestSoon_coalescesMultipleCalls() {
        val s = scheduler(baseFps = 0.5)
        s.start()
        s.requestSoon()
        advance(50)
        s.requestSoon()
        s.requestSoon(100)
        advance(60)
        assertEquals(1, captures)
        advance(500)
        assertEquals(1, captures)
        s.stop()
    }

    @Test
    fun requestSoon_capturesEvenWhenNotDirty() {
        val s = scheduler(baseFps = 0.5)
        s.start()
        s.requestSoon(100)
        advance(100)
        assertEquals(1, captures)
        s.stop()
    }

    @Test
    fun requestSoon_whenStopped_isIgnored() {
        val s = scheduler()
        s.requestSoon()
        assertFalse(hasPending())
        advance(1_000)
        assertEquals(0, captures)
    }

    @Test
    fun slowCaptures_halveFps() {
        val s = scheduler(baseFps = 4.0, budgetMs = 8)
        captureCost = 10
        s.start()
        assertEquals(4.0, s.currentFps, 0.0)
        repeat(2) {
            s.markDirty()
            advance(250)
        }
        assertEquals(4.0, s.currentFps, 0.0) // 2 slow only
        s.markDirty()
        advance(250)
        assertEquals(3, captures)
        assertEquals(2.0, s.currentFps, 0.0)
        // next tick now takes 500 ms
        s.markDirty()
        advance(499)
        assertEquals(3, captures)
        advance(1)
        assertEquals(4, captures)
        s.stop()
    }

    @Test
    fun slowCaptures_floorAtHalfFps() {
        val s = scheduler(baseFps = 1.0)
        captureCost = 10
        s.start()
        repeat(30) {
            s.markDirty()
            advance(2_000)
        }
        assertEquals(0.5, s.currentFps, 0.0)
        s.stop()
    }

    @Test
    fun fastCapture_resetsSlowCounter() {
        val s = scheduler(baseFps = 4.0)
        s.start()
        captureCost = 10
        repeat(2) { s.markDirty(); advance(250) }
        captureCost = 8 // exactly at budget is not slow
        s.markDirty(); advance(250)
        captureCost = 10
        repeat(2) { s.markDirty(); advance(250) }
        assertEquals(4.0, s.currentFps, 0.0)
        s.stop()
    }

    @Test
    fun recoversAfter30s() {
        val s = scheduler(baseFps = 4.0)
        captureCost = 10
        s.start()
        repeat(3) { s.markDirty(); advance(250) }
        assertEquals(2.0, s.currentFps, 0.0)
        captureCost = 0
        clock.advance(29_000)
        advance(500)
        assertEquals(2.0, s.currentFps, 0.0)
        clock.advance(1_000)
        advance(500)
        assertEquals(4.0, s.currentFps, 0.0)
        // back to the 250 ms period
        val before = captures
        s.markDirty()
        advance(250)
        assertEquals(before + 1, captures)
        s.stop()
    }

    @Test
    fun slowCaptureWhileDegraded_postponesRecovery() {
        val s = scheduler(baseFps = 4.0)
        captureCost = 10
        s.start()
        repeat(3) { s.markDirty(); advance(250) }
        clock.advance(25_000)
        s.markDirty(); advance(500) // another slow capture refreshes the timestamp
        clock.advance(25_000)
        advance(500)
        assertTrue(s.currentFps < 4.0)
        s.stop()
    }

    @Test
    fun stop_cancelsPending() {
        val s = scheduler(baseFps = 4.0)
        val root = FrameLayout(context)
        s.start()
        s.attach(root)
        s.markDirty()
        s.requestSoon()
        assertEquals(1, drawListenerCount(root))
        s.stop()
        assertFalse(hasPending())
        assertEquals(0, drawListenerCount(root))
        advance(5 * 60_000)
        assertEquals(0, captures)
    }

    @Test
    fun stop_resetsDirty_andRestartWorks() {
        val s = scheduler(baseFps = 2.0)
        s.start()
        s.markDirty()
        s.stop()
        s.start()
        advance(5_000)
        assertEquals(0, captures) // dirty did not survive stop
        s.markDirty()
        advance(500)
        assertEquals(1, captures)
        s.stop()
    }

    @Test
    fun start_isIdempotent() {
        val s = scheduler(baseFps = 2.0)
        s.start()
        s.start()
        s.start()
        s.markDirty()
        advance(500)
        assertEquals(1, captures)
        s.markDirty()
        advance(500)
        assertEquals(2, captures)
        s.stop()
        assertFalse(hasPending())
    }

    @Test
    fun onDrawListener_onlyMarksDirty() {
        val s = scheduler(baseFps = 2.0)
        val root = FrameLayout(context)
        s.start()
        s.attach(root)
        dispatchDraw(root)
        dispatchDraw(root)
        assertEquals(0, captures) // listener itself never captures
        advance(500)
        assertEquals(1, captures)
        advance(500)
        assertEquals(1, captures)
        s.stop()
    }

    @Test
    fun attach_isIdempotentPerRoot() {
        val s = scheduler()
        val root = FrameLayout(context)
        s.start()
        s.attach(root)
        s.attach(root)
        assertEquals(1, drawListenerCount(root))
        s.detach(root)
        assertEquals(0, drawListenerCount(root))
        s.stop()
    }

    @Test
    fun attach_whenStopped_isIgnored() {
        val s = scheduler()
        val root = FrameLayout(context)
        s.attach(root)
        assertEquals(0, drawListenerCount(root))
    }

    @Test
    fun detach_unknownRoot_doesNotThrow() {
        val s = scheduler()
        s.start()
        s.detach(FrameLayout(context))
        s.stop()
    }

    @Test
    fun detach_afterRootDetached_doesNotThrow() {
        val s = scheduler()
        val root = FrameLayout(context)
        s.start()
        s.attach(root)
        val kill = ViewTreeObserver::class.java.getDeclaredMethod("kill")
        kill.isAccessible = true
        kill.invoke(root.viewTreeObserver)
        assertFalse(root.viewTreeObserver.isAlive)
        s.detach(root)
        s.stop() // also must not throw with a dead observer
    }

    @Test
    fun captureThrows_schedulerKeepsRunning() {
        val s = scheduler(baseFps = 4.0)
        captureAction = { throw IllegalStateException("boom") }
        s.start()
        s.markDirty(); advance(250)
        s.markDirty(); advance(250)
        assertEquals(2, captures)
        assertEquals(4.0, s.currentFps, 0.0)
        captureAction = {}
        s.markDirty(); advance(250)
        assertEquals(3, captures)
        s.stop()
    }

    @Test
    fun captureThrowsThreeTimes_backsOff() {
        val s = scheduler(baseFps = 4.0)
        captureAction = { throw IllegalStateException("boom") }
        s.start()
        repeat(3) { s.markDirty(); advance(250) }
        assertEquals(2.0, s.currentFps, 0.0)
        s.stop()
    }
}
