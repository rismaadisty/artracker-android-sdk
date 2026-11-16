package id.art.tracker.telemetry

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.BeforeSend
import id.art.tracker.LogLevel
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.Clock
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SharedPrefsStore
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.testutil.QueueReader
import id.art.tracker.transport.ReplayStore
import id.art.tracker.transport.TransportConfigStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The watchdog wired into the core: which API levels get one, when it runs, and what a report becomes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AnrWatchdogCoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
    private val hookSaw = CopyOnWriteArrayList<TelemetryItem>()
    private var now = 1_000L
    private var core: ARTrackerCore? = null
    private val frames = arrayOf(StackTraceElement("com.host.Feed", "load", "Feed.kt", 42), StackTraceElement("com.host.Repo", "get", "Repo.kt", 7))

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        clean()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        clean()
    }

    private fun clean() {
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
        app.getSharedPreferences(TransportConfigStore.PREFS_NAME, 0).edit().clear().commit()
    }

    private fun config(beforeSend: BeforeSend? = null) = ARTrackerConfig(
        endpoint = "http://127.0.0.1:9",
        apiKey = "art_live_" + "a".repeat(43),
        environment = "test",
        appVersion = "1.0",
        beforeSend = beforeSend ?: BeforeSend { it.also(seen::add) },
    )

    /** A core whose watchdog has a fake clock and a thread that is never started; `fake = false` = the real factory. */
    private fun startCore(fake: Boolean = true, beforeSend: BeforeSend? = null, clock: Clock = Clock.SYSTEM): ARTrackerCore {
        val factory: (((Long, Array<StackTraceElement>) -> Unit) -> AnrWatchdog?)? = if (fake) {
            { onAnr ->
                AnrWatchdog(
                    mainHandler = Handler(Looper.getMainLooper()),
                    onAnr = onAnr,
                    uptime = { now },
                    threadFactory = { r -> object : Thread(r) { override fun start() = Unit } },
                    mainStack = { frames },
                )
            }
        } else {
            null
        }
        val c = ARTrackerCore(app, config(beforeSend), clock, anrWatchdogFactory = factory)
        ARTrackerCore.install(c)
        c.start()
        core = c
        return c
    }

    private fun settle(): List<TelemetryItem> {
        runBlocking { withTimeout(15_000) { core!!.awaitIdle() } }
        return seen.toList()
    }

    private fun List<TelemetryItem>.anrs() = filter { it.name == "anr" }

    @Test
    fun defaultWatchdogExistsOnApi29() {
        val c = startCore(fake = false)

        assertNotNull(c.anrWatchdog)
        assertFalse("not before the foreground", c.anrWatchdog!!.isRunning)
    }

    @Test
    @Config(sdk = [30])
    fun noWatchdogOnApi30() {
        val c = startCore(fake = false)
        ProcessLifecycleTestUtil.start()

        assertNull(c.anrWatchdog)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == AnrWatchdog.THREAD_NAME })
    }

    @Test
    fun runsOnlyInTheForeground() {
        val c = startCore()
        ProcessLifecycleTestUtil.start()
        assertTrue(c.anrWatchdog!!.isRunning)

        ProcessLifecycleTestUtil.stop()

        assertFalse(c.anrWatchdog!!.isRunning)
    }

    @Test
    fun blockedMainThreadBecomesAnErrorItem() {
        val c = startCore()
        ProcessLifecycleTestUtil.start()
        c.screen("Feed")

        c.onMainThreadBlocked(5_000, frames)

        val item = settle().anrs().single()
        assertEquals(LogLevel.ERROR, item.level)
        assertEquals("Main thread blocked for 5000 ms", item.message)
        assertEquals("watchdog", item.attributes["source"])
        assertEquals(5_000L, item.attributes["blocked_ms"])
        assertTrue((item.attributes["main_thread_stack"] as String).startsWith("ANR: main thread blocked for 5000 ms\n\tat com.host.Feed.load"))
        assertEquals("Feed", item.attributes["screen"])
        assertTrue(item.attributes["breadcrumbs"] is List<*>)
    }

    @Test
    fun identicalHangsAreCappedPerSession() {
        val c = startCore()
        ProcessLifecycleTestUtil.start()

        repeat(12) { c.onMainThreadBlocked(5_000, frames) }
        assertEquals(10, settle().anrs().size)

        c.onMainThreadBlocked(5_000, arrayOf(StackTraceElement("com.host.Other", "wait", "Other.kt", 1)))
        assertEquals("another top frame is another hang", 11, settle().anrs().size)
    }

    @Test
    fun reportAfterOptOutIsDropped() {
        val c = startCore()
        ProcessLifecycleTestUtil.start()

        c.optOut(true)
        c.onMainThreadBlocked(5_000, frames)

        assertTrue(settle().anrs().isEmpty())
        assertFalse(c.anrWatchdog!!.isRunning)
    }

    @Test
    fun optInInForegroundRestartsTheWatchdog() {
        val c = startCore()
        ProcessLifecycleTestUtil.start()

        c.optOut(true)
        c.optOut(false)

        assertTrue(c.anrWatchdog!!.isRunning)
    }

    @Test
    fun foregroundEnteredDuringATrip_startsTheWatchdogWhenTheTripExpires() {
        val clock = FakeClock(1_700_000_000_000L)
        // A trip written by an earlier run, still running when the app comes to the foreground.
        SharedPrefsStore(app, TransportConfigStore.PREFS_NAME).putLong(KillSwitch.KEY_DISABLED_UNTIL, clock.nowMs() + 60_000)
        val c = startCore(clock = clock)
        ProcessLifecycleTestUtil.start()
        assertFalse("disabled while the trip lasts", c.anrWatchdog!!.isRunning)

        clock.advance(61_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(61_000))

        assertTrue("it comes back with the rest of the recording", c.anrWatchdog!!.isRunning)
    }

    @Test
    fun reportArrivingAfterBackgroundIsDropped() {
        val c = startCore()
        ProcessLifecycleTestUtil.start()
        ProcessLifecycleTestUtil.stop()

        c.onMainThreadBlocked(5_000, frames) // built on the watchdog thread while the app went to the background

        assertTrue(settle().anrs().isEmpty())
    }

    @Test
    fun foregroundAfterBackgroundDoesNotReportAStaleTick() {
        val c = startCore()
        ProcessLifecycleTestUtil.start()
        val w = c.anrWatchdog!!
        now += 1_000
        w.checkOnce() // posts a tick
        now += 4_000
        ProcessLifecycleTestUtil.stop()
        now += 10_000
        ProcessLifecycleTestUtil.start()
        now += 1_000
        w.checkOnce() // a fresh episode: it only posts

        assertTrue(settle().anrs().isEmpty())
    }

    @Test
    fun beforeSendCanDropAnAnr() {
        val c = startCore(beforeSend = BeforeSend { hookSaw += it; if (it.name == "anr") null else it.also(seen::add) })
        ProcessLifecycleTestUtil.start()

        c.onMainThreadBlocked(5_000, frames)

        assertTrue(settle().anrs().isEmpty())
        assertEquals("the hook did see it", 1, hookSaw.anrs().size)
    }

    @Test
    fun sensitiveScreenNameIsRedacted() {
        val c = startCore()
        ProcessLifecycleTestUtil.start()
        c.screen("user a@b.co")

        c.onMainThreadBlocked(5_000, frames)
        settle()

        // beforeSend sees the item before redaction; what is stored is what counts.
        val stored = QueueReader.drain(app).single { it.contains("\"name\":\"anr\"") }
        assertFalse(stored.contains("a@b.co"))
    }

    @Test
    fun shutdownStopsTheWatchdog() {
        val c = startCore()
        ProcessLifecycleTestUtil.start()
        val w = c.anrWatchdog!!
        assertTrue(w.isRunning)

        ARTracker.resetForTest()

        assertFalse(w.isRunning)
    }
}
