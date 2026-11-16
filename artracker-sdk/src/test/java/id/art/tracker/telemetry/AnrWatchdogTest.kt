package id.art.tracker.telemetry

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The one-round logic of the watchdog, driven by hand: a fake clock, a paused main looper, no real thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AnrWatchdogTest {
    private var now = 1_000L
    private var debugger = false
    private var uptimeCalls = 0
    private val handler = Handler(Looper.getMainLooper())
    private val reports = mutableListOf<Long>()
    private val runnables = mutableListOf<Runnable>()
    private val stack = arrayOf(StackTraceElement("com.host.Feed", "load", "Feed.kt", 42))

    private fun watchdog(
        onAnr: (Long, Array<StackTraceElement>) -> Unit = { blocked, _ -> reports += blocked },
        sleep: (Long) -> Unit = {},
    ) = AnrWatchdog(
        mainHandler = handler,
        onAnr = onAnr,
        uptime = { uptimeCalls++; now },
        isDebuggerConnected = { debugger },
        mainStack = { stack },
        // A thread that is never started: its Runnable is kept and run by hand where a test needs the loop.
        threadFactory = { r ->
            runnables += r
            object : Thread(r) {
                override fun start() = Unit
            }
        },
        sleep = sleep,
    )

    private fun AnrWatchdog.step(ms: Long) {
        now += ms
        checkOnce()
    }

    private fun mainResponds() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun respondingMainThreadNeverReports() {
        val w = watchdog().also { it.start() }
        repeat(20) {
            w.step(1_000)
            mainResponds()
        }
        assertTrue(reports.isEmpty())
    }

    @Test
    fun blockedForFiveSecondsReportsOnce() {
        val w = watchdog().also { it.start() }
        w.step(1_000) // posts the tick
        repeat(5) { w.step(1_000) }
        assertEquals(listOf(5_000L), reports)
    }

    @Test
    fun fourSecondsIsNotAnAnr() {
        val w = watchdog().also { it.start() }
        w.step(1_000)
        repeat(4) { w.step(1_000) }
        assertTrue(reports.isEmpty())
    }

    @Test
    fun oneReportPerEpisode() {
        val w = watchdog().also { it.start() }
        repeat(12) { w.step(1_000) }
        assertEquals(1, reports.size)
    }

    @Test
    fun rearmsOnlyAfterTheMainThreadResponds() {
        val w = watchdog().also { it.start() }
        repeat(7) { w.step(1_000) }
        assertEquals(1, reports.size)

        mainResponds()
        w.step(1_000) // posts a new tick
        repeat(5) { w.step(1_000) }

        assertEquals(2, reports.size)
    }

    @Test
    fun debuggerConnectedReportsNothing() {
        debugger = true
        val w = watchdog().also { it.start() }
        repeat(7) { w.step(1_000) }
        assertTrue(reports.isEmpty())
    }

    @Test
    fun debuggerDetachedMidEpisodeStillReportsNothing() {
        debugger = true
        val w = watchdog().also { it.start() }
        repeat(7) { w.step(1_000) } // the threshold passes while the debugger is attached
        debugger = false
        repeat(5) { w.step(1_000) } // the same hang goes on: still nothing
        assertTrue(reports.isEmpty())

        mainResponds()
        w.step(1_000)
        repeat(5) { w.step(1_000) } // a new hang, no debugger
        assertEquals(1, reports.size)
    }

    @Test
    fun lateWatchdogWakeupRebasesTheEpisode() {
        val w = watchdog().also { it.start() }
        w.step(1_000) // posts the tick
        w.step(30_000) // the watchdog itself slept through a doze: it is not the main thread that is stuck
        assertTrue(reports.isEmpty())
        repeat(4) { w.step(1_000) }
        assertTrue("4 s since the rebase", reports.isEmpty())

        w.step(1_000)
        assertEquals(1, reports.size)
    }

    @Test
    fun restartAfterStopStartsAFreshEpisode() {
        val w = watchdog().also { it.start() }
        w.step(1_000)
        repeat(4) { w.step(1_000) }
        w.stop()
        w.start()
        repeat(2) { w.step(1_000) }
        assertTrue("the old tick is gone and postedAt is new", reports.isEmpty())
    }

    @Test
    fun stopRemovesThePendingTick() {
        val w = watchdog().also { it.start() }
        w.step(1_000)
        assertFalse("the tick is waiting on the main looper", shadowOf(Looper.getMainLooper()).isIdle)

        w.stop()

        assertTrue(shadowOf(Looper.getMainLooper()).isIdle)
    }

    @Test
    fun startIsIdempotent() {
        val w = watchdog()
        w.start()
        w.start()
        assertEquals(1, runnables.size)
        assertTrue(w.isRunning)

        w.stop()
        assertFalse(w.isRunning)
        w.start()
        assertEquals(2, runnables.size)
    }

    @Test
    fun loopExitsAfterStop() {
        var rounds = 0
        lateinit var w: AnrWatchdog
        w = watchdog(sleep = { if (++rounds == 2) w.stop() })
        w.start()

        runnables.single().run() // returns: it does not spin forever

        assertEquals("the round after stop() is not checked", 1, uptimeCalls)
    }

    @Test
    fun throwingOnAnrDoesNotKillTheLoop() {
        var rounds = 0
        lateinit var w: AnrWatchdog
        w = watchdog(
            onAnr = { _, _ -> error("boom") },
            sleep = {
                now += 1_000
                if (++rounds == 8) w.stop()
            },
        )
        w.start()

        runnables.single().run() // must not throw

        assertEquals(8, rounds)
    }
}
