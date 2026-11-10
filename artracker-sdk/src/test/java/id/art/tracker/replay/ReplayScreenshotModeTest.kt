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
import id.art.tracker.ReplayMode
import id.art.tracker.ReplayQuality
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.capture.ViewWalker
import id.art.tracker.replay.screenshot.BitmapPool
import id.art.tracker.replay.screenshot.ByteBudgetGovernor
import id.art.tracker.replay.screenshot.MaskCollector
import id.art.tracker.replay.screenshot.PixelCopyApi
import id.art.tracker.replay.screenshot.ScreenChunker
import id.art.tracker.replay.screenshot.ScreenshotCapturer
import id.art.tracker.replay.screenshot.ScreenshotEncoder
import id.art.tracker.replay.screenshot.ScreenshotKit
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.InMemoryStore
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.transport.NewReplayBatch
import id.art.tracker.transport.ReplayFormat
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshot mode of the replay controller: mode resolution, the server switch, capture, masking, chunks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplayScreenshotModeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val looper: Looper = Looper.getMainLooper()
    private val sinkBatches = CopyOnWriteArrayList<NewReplayBatch>()
    private val clock = FakeClock(1_700_000_000_000L)
    private val dispatcher = ManualDispatcher()
    private val ioDispatcher = ManualDispatcher()
    private val cache = ReplayRemoteConfigCache(InMemoryStore())
    private var fetchResult: RemoteReplayConfig? = null
    private var fetches = 0
    private var clearedQueue = 0
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

    private fun startCore(replay: ReplayConfig = ReplayConfig(enabled = true, sampleRate = 1.0, mode = ReplayMode.SCREENSHOT, quality = ReplayQuality.STANDARD)) {
        val cfg = ARTrackerConfig(
            endpoint = "http://127.0.0.1:9", apiKey = "art_live_" + "a".repeat(43), environment = "test", appVersion = "1.0", replay = replay,
        )
        val sink = ReplaySink { sinkBatches += it }
        core = ARTrackerCore(app, cfg, clock, replayFactory = { c, handler ->
            val kit = ScreenshotKit(
                capturer = ScreenshotCapturer(
                    BitmapPool(), MaskCollector(MaskingPolicy(c.config.privacy)), handler, sdkInt = 25,
                    pixelCopy = PixelCopyApi { _, _, done -> done(PixelCopyApi.ERROR) },
                ),
                encoder = ScreenshotEncoder(),
                chunker = ScreenChunker(sink, c.clock, c.config.environment, { c.identity.userId }),
                governorFactory = { fps, q -> ByteBudgetGovernor(fps, q, budgetBytes = governorBudget) },
            )
            ReplayController.create(
                c, ViewWalker(MaskingPolicy(c.config.privacy)), handler, { _, _ -> true }, dispatcher,
                screenshot = kit,
                remote = ReplayRemote(cache) { fetches++; fetchResult },
                clearScreenshotQueue = { clearedQueue++ },
                io = ioDispatcher,
            )
        }, replaySink = sink)
        ARTrackerCore.install(core)
        core.start()
        ProcessLifecycleTestUtil.start()
    }

    private fun controller(): ReplayController = core.replayController!!

    private fun idle(ms: Long = 0) {
        if (ms == 0L) shadowOf(looper).idle() else shadowOf(looper).idleFor(Duration.ofMillis(ms))
    }

    /** Runs the background fetch and applies its answer on the main thread. */
    private fun applyRemoteConfig() {
        ioDispatcher.runAll()
        idle()
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

    private fun screenBatches() = sinkBatches.filter { it.format == ReplayFormat.SCREEN_V1 }
    private fun rrwebBatches() = sinkBatches.filter { it.format == ReplayFormat.RRWEB }

    private fun json(b: NewReplayBatch) =
        JSONObject(GZIPInputStream(ByteArrayInputStream(b.gzipBody)).readBytes().toString(Charsets.UTF_8))

    private fun frames(b: NewReplayBatch) = json(b).getJSONArray("frames")

    private fun firstFrameImage(): android.graphics.Bitmap {
        val data = frames(screenBatches().first()).getJSONObject(0).getString("data")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test
    fun cachedEnabled_recordsScreenshots_fromTheStart() {
        cache.save(RemoteReplayConfig(true, null))
        fetchResult = RemoteReplayConfig(true, null)
        startCore()
        launch()
        capture()
        flush()

        assertTrue(rrwebBatches().isEmpty())
        val chunk = json(screenBatches().single())
        assertEquals("artracker-screen-v1", chunk.getString("format"))
        assertEquals("android", chunk.getString("platform"))
        assertTrue(chunk.getJSONArray("frames").length() >= 1)
    }

    @Test
    fun beforeTheFirstAnswer_itRecordsWireframes_thenSwitchesAndForcesAFirstScreenshot() {
        fetchResult = RemoteReplayConfig(true, 4.0)
        startCore()
        launch()
        capture() // no cache yet: wireframe
        applyRemoteConfig()
        capture() // now a screenshot, never suppressed as a duplicate
        flush()

        assertEquals(1, rrwebBatches().size)
        assertEquals(1, screenBatches().size)
        assertEquals(true, cache.screenshotEnabled)
        assertEquals(4.0, cache.maxFps!!, 0.0)
    }

    @Test
    fun theDefaultWireframeMode_neverFetchesAndNeverTakesScreenshots() {
        cache.save(RemoteReplayConfig(true, null))
        startCore(ReplayConfig(enabled = true, sampleRate = 1.0)) // mode = WIREFRAME
        launch()
        capture()
        flush()
        applyRemoteConfig()
        assertEquals(0, fetches)
        assertTrue(screenBatches().isEmpty())
        assertTrue(rrwebBatches().isNotEmpty())
    }

    @Test
    fun cachedDisabled_recordsWireframes_untilTheServerSaysOtherwise() {
        cache.save(RemoteReplayConfig(false, null))
        fetchResult = RemoteReplayConfig(false, null)
        startCore()
        launch()
        capture()
        applyRemoteConfig()
        capture()
        flush()
        assertTrue(screenBatches().isEmpty())
        assertTrue(rrwebBatches().isNotEmpty())
    }

    @Test
    fun anUnreachableConfig_keepsTheCachedChoice() {
        cache.save(RemoteReplayConfig(true, null))
        fetchResult = null
        startCore()
        launch()
        applyRemoteConfig()
        capture()
        flush()
        assertEquals(1, screenBatches().size)
        assertEquals(true, cache.screenshotEnabled)
    }

    @Test
    fun theServerTurnsItOffWhileRunning_backToWireframes_withAFullSnapshot_andQueuedScreenshotsCleared() {
        cache.save(RemoteReplayConfig(true, null))
        fetchResult = RemoteReplayConfig(false, null)
        startCore()
        launch()
        capture()
        applyRemoteConfig() // answer: disabled
        assertEquals(false, cache.screenshotEnabled)
        assertEquals(1, clearedQueue)
        capture()
        flush()
        val wire = rrwebBatches()
        assertTrue(wire.isNotEmpty())
        val types = GZIPInputStream(ByteArrayInputStream(wire.first().gzipBody)).readBytes().toString(Charsets.UTF_8)
        assertTrue("a full snapshot (type 2) must open the wireframe recording", types.contains("\"type\":2"))
    }

    @Test
    fun a403ScreenshotDisabled_switchesTheRunningSession_andLogsOnce() {
        cache.save(RemoteReplayConfig(true, null))
        fetchResult = RemoteReplayConfig(true, null)
        startCore()
        launch()
        capture()
        controller().onScreenshotDisabled()
        idle()
        assertEquals(false, cache.screenshotEnabled)
        assertEquals(1, clearedQueue)
        controller().onScreenshotDisabled() // a second 403 changes nothing
        idle()
        assertEquals(1, clearedQueue)
        capture()
        flush()
        assertTrue(rrwebBatches().isNotEmpty())
    }

    @Test
    fun sensitiveViews_areMaskPixelsInTheEncodedFrame() {
        cache.save(RemoteReplayConfig(true, null))
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
        cache.save(RemoteReplayConfig(true, null))
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
        cache.save(RemoteReplayConfig(true, null))
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
        cache.save(RemoteReplayConfig(true, null))
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
    fun serverMaxFps_capsTheScreenshotRate() {
        cache.save(RemoteReplayConfig(true, 1.0))
        fetchResult = RemoteReplayConfig(true, 1.0)
        startCore(ReplayConfig(enabled = true, sampleRate = 1.0, framesPerSecond = 4.0, mode = ReplayMode.SCREENSHOT))
        launch()
        applyRemoteConfig()
        assertEquals(1.0, schedulerCap()!!, 0.0)
    }

    @Test
    fun whenTheByteBudgetIsExceeded_theFpsIsHalved() {
        governorBudget = 1 // any frame is over budget
        cache.save(RemoteReplayConfig(true, null))
        startCore(ReplayConfig(enabled = true, sampleRate = 1.0, framesPerSecond = 2.0, mode = ReplayMode.SCREENSHOT))
        launch()
        capture()
        idle()
        assertEquals(1.0, schedulerCap()!!, 0.0)
    }

    @Test
    fun theWireframeFallback_isNeverFasterThan4Fps() {
        cache.save(RemoteReplayConfig(false, null))
        startCore(ReplayConfig(enabled = true, sampleRate = 1.0, framesPerSecond = 8.0, mode = ReplayMode.SCREENSHOT))
        launch()
        assertEquals(4.0, schedulerCap()!!, 0.0)
    }

    @Test
    fun aFrameIsNeverEmitted_whenTheSdkIsOptedOut() {
        cache.save(RemoteReplayConfig(true, null))
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
