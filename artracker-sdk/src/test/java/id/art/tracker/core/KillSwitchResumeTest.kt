package id.art.tracker.core

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.ReplayConfig
import id.art.tracker.replay.ManualDispatcher
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.testutil.testReplayController
import id.art.tracker.transport.ReplayStore
import id.art.tracker.transport.TransportConfigStore
import java.time.Duration
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * After a 401 trips the kill-switch, recording must come back by itself once the deadline passes, while the app stays in
 * the foreground. Two clocks are in play: the SDK's [FakeClock] (what `isActive` reads) and the main looper's own time
 * (what the scheduled resume runs on); [advance] moves both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KillSwitchResumeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val looper: Looper = Looper.getMainLooper()
    private val clock = FakeClock(1_700_000_000_000L)
    private val server = MockWebServer()
    private val dispatcher = ManualDispatcher()
    private val trip = KillSwitch.DEFAULT_TRIP_MS
    private val transportPrefs get() = SharedPrefsStore(app, TransportConfigStore.PREFS_NAME)

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        cleanStorage()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        server.start()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        runCatching { server.shutdown() }
        cleanStorage()
    }

    private fun cleanStorage() {
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
        app.getSharedPreferences(TransportConfigStore.PREFS_NAME, 0).edit().clear().commit()
    }

    private fun startCore(): ARTrackerCore {
        val core = ARTrackerCore(
            app,
            ARTrackerConfig(
                endpoint = server.url("/").toString().trimEnd('/'),
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
                replay = ReplayConfig(enabled = true, sampleRate = 1.0),
            ),
            clock,
            replayFactory = { c, handler -> testReplayController(c, handler, dispatcher) },
        )
        ARTrackerCore.install(core)
        core.start()
        ProcessLifecycleTestUtil.start()
        shadowOf(looper).idle()
        return core
    }

    /** Sends one event; the server answers 401, which trips the kill-switch. Returns once the SDK is disabled. */
    private fun tripWith401(core: ARTrackerCore) {
        ARTracker.track("trigger")
        ARTracker.flush()
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (!core.isDisabled() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("the 401 should have tripped the kill-switch", core.isDisabled())
        // isDisabled() flips as soon as the deadline is stored, a moment BEFORE the trip listener (on the flusher's
        // thread) has posted its work to the main thread. Wait for both effects: replay stopped, resume scheduled.
        while (System.nanoTime() < deadline) {
            shadowOf(looper).idle()
            if (!recording(core) && shadowOf(looper).nextScheduledTaskTime > Duration.ZERO) return
            Thread.sleep(20)
        }
        throw AssertionError("the trip did not stop replay and schedule the resume in time")
    }

    private fun advance(ms: Long) {
        clock.advance(ms)
        shadowOf(looper).idleFor(Duration.ofMillis(ms))
    }

    private fun recording(core: ARTrackerCore) = core.replayController!!.isRunning

    @Test
    fun replayResumesInForegroundAfterTripExpires() {
        val core = startCore()
        assertTrue("sanity: recording before the trip", recording(core))

        tripWith401(core)
        assertFalse(recording(core))

        advance(trip + 1_000)

        assertFalse(core.isDisabled())
        assertTrue(recording(core))
    }

    @Test
    fun aTripPersistedByAnEarlierRunIsScheduledAtStart() {
        transportPrefs.putLong(KillSwitch.KEY_DISABLED_UNTIL, clock.nowMs() + 60_000)
        val core = startCore()
        assertTrue(core.isDisabled())
        assertFalse(recording(core))

        advance(61_000)

        assertFalse(core.isDisabled())
        assertTrue(recording(core))
    }

    @Test
    fun retripMovesTheResumeToTheNewDeadline() {
        val core = startCore()
        tripWith401(core)
        advance(trip / 2)
        // A trip written by another instance over the same prefs (what a fresh-process FlushWorker does): the core
        // is not told, so the scheduled resume has to notice the later deadline itself.
        KillSwitch(transportPrefs, clock).trip()
        assertTrue(core.isDisabled())

        advance(trip / 2 + 1_000) // past the first deadline
        assertTrue(core.isDisabled())
        assertFalse(recording(core))

        advance(trip / 2) // past the second deadline
        assertFalse(core.isDisabled())
        assertTrue(recording(core))
    }

    @Test
    fun expiryInBackgroundStartsNothing() {
        val core = startCore()
        tripWith401(core)
        ProcessLifecycleTestUtil.stop()
        shadowOf(looper).idle()
        val requestsBefore = server.requestCount

        advance(trip + 1_000)

        assertFalse(recording(core))
        assertEquals(requestsBefore, server.requestCount)

        ProcessLifecycleTestUtil.start()
        shadowOf(looper).idle()
        assertTrue(recording(core))
    }

    @Test
    fun foregroundRearmsTheResumeFromTheWallClock() {
        val core = startCore()
        tripWith401(core)
        ProcessLifecycleTestUtil.stop()
        shadowOf(looper).idle()
        // The device slept: wall-clock time passed while uptime (which the looper's delay counts) did not.
        clock.advance(trip - 60_000)

        ProcessLifecycleTestUtil.start()
        shadowOf(looper).idle()
        assertTrue(core.isDisabled())
        assertFalse(recording(core))

        advance(2 * 60_000) // the deadline is 60 s of wall time away, so the resume must fire within this
        assertFalse(core.isDisabled())
        assertTrue(recording(core))
    }

    @Test
    fun optOutDuringTripStaysDisabledAfterExpiry() {
        val core = startCore()
        tripWith401(core)
        ARTracker.optOut(true)

        advance(trip + 1_000)

        assertTrue(core.isDisabled())
        assertFalse(recording(core))
    }

    @Test
    fun shutdownCancelsThePendingResume() {
        val core = startCore()
        tripWith401(core)
        val replay = core.replayController!!

        ARTracker.resetForTest()
        advance(trip + 1_000)

        // A resume that survived the shutdown would re-evaluate the controller and start recording again.
        assertFalse(replay.isRunning)
    }
}
