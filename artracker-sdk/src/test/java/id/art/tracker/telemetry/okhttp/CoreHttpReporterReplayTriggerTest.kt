package id.art.tracker.telemetry.okhttp

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.ReplayConfig
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.replay.ManualDispatcher
import id.art.tracker.replay.ReplaySink
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.testutil.testReplayController
import id.art.tracker.testutil.testScreenshotKit
import id.art.tracker.transport.NewReplayBatch
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An API response is an important UI change for replay; a request to the SDK's own endpoint is not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreHttpReporterReplayTriggerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val looper: Looper = Looper.getMainLooper()
    private val batches = CopyOnWriteArrayList<NewReplayBatch>()
    private val dispatcher = ManualDispatcher()
    private lateinit var core: ARTrackerCore

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
    }

    private fun record(url: String, host: String) =
        HttpRecord("GET", url, host, 200, 12, null, null, "http/1.1", null)

    private fun settleFrames(ms: Long) {
        shadowOf(looper).idleFor(Duration.ofMillis(ms))
        dispatcher.runAll()
        core.replayController!!.flushChunks()
        shadowOf(looper).idle()
        dispatcher.runAll()
    }

    @Test
    fun httpResponse_triggersReplay_sdkEndpointDoesNot() {
        val sink = ReplaySink { batches += it }
        core = ARTrackerCore(
            app,
            ARTrackerConfig(
                endpoint = "http://127.0.0.1:9",
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
                replay = ReplayConfig(enabled = true, sampleRate = 1.0),
            ),
            replayFactory = { c, handler ->
                testReplayController(c, handler, dispatcher, testScreenshotKit(c, handler, sink), { _, _ -> true })
            },
            replaySink = sink,
            appStartFactory = { _, _ -> null },
        )
        ARTrackerCore.install(core)
        core.start()
        ProcessLifecycleTestUtil.start()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(TextView(activity).apply { text = "orders" })
        settleFrames(1_000)
        val baseline = batches.size
        assertEquals(1, baseline) // the first frame

        val reporter = CoreHttpReporter()
        reporter.report(record("http://127.0.0.1:9/v1/ingest", "127.0.0.1")) // the SDK's own upload
        settleFrames(1_000)
        assertEquals(baseline, batches.size)

        reporter.report(record("http://api.example.com/orders", "api.example.com"))
        settleFrames(1_000)
        assertEquals(baseline + 1, batches.size)
    }
}
