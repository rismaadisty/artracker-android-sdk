package id.art.tracker.replay

import android.app.Activity
import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.ReplayConfig
import id.art.tracker.ReplayQuality
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.replay.screenshot.ByteBudgetGovernor
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.testutil.testReplayController
import id.art.tracker.testutil.testScreenshotKit
import id.art.tracker.transport.NewReplayBatch
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshot capture of the replay controller: masking, deduplication, touches, the byte budget and the chunks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplayScreenshotCaptureTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val looper: Looper = Looper.getMainLooper()
    private val sinkBatches = CopyOnWriteArrayList<NewReplayBatch>()
    private val clock = FakeClock(1_700_000_000_000L)
    private val dispatcher = ManualDispatcher()
    private var governorBudget = ByteBudgetGovernor.DEFAULT_BUDGET_BYTES
    private lateinit var core: ARTrackerCore

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ClassAvailability.resetOverrides()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ClassAvailability.resetOverrides()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
    }

    private fun startCore(replay: ReplayConfig = ReplayConfig(enabled = true, sampleRate = 1.0, quality = ReplayQuality.STANDARD)) {
        val cfg = ARTrackerConfig(
            endpoint = "http://127.0.0.1:9", apiKey = "art_live_" + "a".repeat(43), environment = "test", appVersion = "1.0", replay = replay,
        )
        val sink = ReplaySink { sinkBatches += it }
        core = ARTrackerCore(app, cfg, clock, replayFactory = { c, handler ->
            testReplayController(c, handler, dispatcher, testScreenshotKit(c, handler, sink, governorBudget))
        }, replaySink = sink)
        ARTrackerCore.install(core)
        core.start()
        ProcessLifecycleTestUtil.start()
    }

    private fun controller(): ReplayController = core.replayController!!

    private fun idle(ms: Long = 0) {
        if (ms == 0L) shadowOf(looper).idle() else shadowOf(looper).idleFor(Duration.ofMillis(ms))
    }

    private lateinit var secret: EditText

    private fun launch(): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(activity).apply { text = "public text" }, LinearLayout.LayoutParams(300, 60))
        secret = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundColor(Color.RED)
        }
        root.addView(secret, LinearLayout.LayoutParams(300, 100))
        activity.setContentView(root)
        idle(50)
        return activity
    }

    private fun capture() {
        controller().captureFrame()
        dispatcher.runAll()
    }

    private fun flush() {
        controller().flushChunks()
        runBlocking { withTimeout(15_000) { core.awaitIdle() } }
        dispatcher.runAll()
    }

    private fun screenBatches() = sinkBatches.toList()

    private fun json(b: NewReplayBatch) =
        JSONObject(GZIPInputStream(ByteArrayInputStream(b.gzipBody)).readBytes().toString(Charsets.UTF_8))

    private fun frames(b: NewReplayBatch) = json(b).getJSONArray("frames")

    private fun firstFrameImage(): android.graphics.Bitmap {
        val data = frames(screenBatches().first()).getJSONObject(0).getString("data")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test
    fun recordsScreenshots_fromTheStart() {
        startCore()
        launch()
        capture()
        flush()

        val chunk = json(screenBatches().single())
        assertEquals("artracker-screen-v1", chunk.getString("format"))
        assertEquals("android", chunk.getString("platform"))
        assertTrue(chunk.getJSONArray("frames").length() >= 1)
    }

    @Test
    fun sensitiveViews_areMaskPixelsInTheEncodedFrame() {
        startCore()
        launch()
        capture()
        flush()
        val img = firstFrameImage()
        val loc = IntArray(2).also { secret.getLocationOnScreen(it) }
        val p = img.getPixel(loc[0] + 150, loc[1] + 50)
        assertFalse("the red background of the password field must be hidden", Color.red(p) > 200 && Color.green(p) < 60)
        assertTrue("grey mask expected, was ${Integer.toHexString(p)}", Math.abs(Color.red(p) - Color.green(p)) < 40)
    }

    @Test
    fun anUnchangedScreen_addsNoSecondFrame_butAScreenChangeDoes() {
        startCore()
        launch()
        capture()
        capture()
        flush()
        assertEquals(1, frames(screenBatches().single()).length())

        controller().onScreenChanged()
        idle(200)
        capture()
        flush()
        assertEquals(1, frames(screenBatches().last()).length()) // forced: not a duplicate
        assertEquals(2, screenBatches().size)
    }

    @Test
    fun touches_ride_onTheScreenChunk() {
        startCore()
        val activity = launch()
        capture()
        activity.window.callback.dispatchTouchEvent(MotionEvent.obtain(0, 1L, MotionEvent.ACTION_DOWN, 30f, 40f, 0))
        dispatcher.runAll()
        flush()
        val touches = json(screenBatches().single()).getJSONArray("touches")
        assertEquals(1, touches.length())
        assertEquals("down", touches.getJSONObject(0).getString("kind"))
    }

    @Test
    fun stopReplay_closesTheScreenChunk() {
        startCore()
        launch()
        capture()
        ARTracker.stopReplay()
        idle()
        runBlocking { withTimeout(15_000) { core.awaitIdle() } }
        dispatcher.runAll()
        assertEquals(1, screenBatches().size)
    }

    @Test
    fun whenTheByteBudgetIsExceeded_theFpsIsHalved() {
        governorBudget = 1 // any frame is over budget
        startCore(ReplayConfig(enabled = true, sampleRate = 1.0, framesPerSecond = 2.0))
        launch()
        capture()
        idle()
        assertEquals(1.0, schedulerCap()!!, 0.0)
    }

    @Test
    fun aFrameIsNeverEmitted_whenTheSdkIsOptedOut() {
        startCore()
        launch()
        capture()
        ARTracker.optOut(true)
        idle()
        flush()
        assertTrue(screenBatches().isEmpty())
    }

    private fun schedulerCap(): Double? {
        val f = ReplayController::class.java.getDeclaredField("scheduler").apply { isAccessible = true }
        return (f.get(controller()) as id.art.tracker.replay.capture.FrameScheduler).externalFpsCap
    }
}
