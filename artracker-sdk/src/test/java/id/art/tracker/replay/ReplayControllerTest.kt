package id.art.tracker.replay

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.BeforeSend
import id.art.tracker.ReplayConfig
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.replay.touch.TouchRecorder
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.testutil.testReplayController
import id.art.tracker.testutil.testScreenshotKit
import id.art.tracker.transport.NewReplayBatch
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Runs the controller's background work on the test thread, only when asked: fully deterministic. */
internal class ManualDispatcher : CoroutineDispatcher() {
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.add(block)
    }

    val pending: Int get() = tasks.size

    fun runAll() {
        while (true) (tasks.poll() ?: return).run()
    }
}

/** Lifecycle of the screenshot replay controller: when it records, what it flushes, and what it never emits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplayControllerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val looper: Looper = Looper.getMainLooper()
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
    private val sinkBatches = CopyOnWriteArrayList<NewReplayBatch>()
    private val sink = ReplaySink { sinkBatches += it }
    private val clock = FakeClock(1_700_000_000_000L)
    private val dispatcher = ManualDispatcher()
    private var sampler: (String, Double) -> Boolean = Sampling::isSampled
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

    private fun config(replay: ReplayConfig) = ARTrackerConfig(
        endpoint = "http://127.0.0.1:9",
        apiKey = "art_live_" + "a".repeat(43),
        environment = "test",
        appVersion = "1.0",
        replay = replay,
        beforeSend = BeforeSend { it.also(seen::add) },
    )

    /** A started core whose replay controller runs its background work on [dispatcher]. */
    private fun startCore(
        replay: ReplayConfig = ReplayConfig(enabled = true, sampleRate = 1.0),
        useDefaultFactory: Boolean = false,
        foreground: Boolean = true,
    ): ARTrackerCore {
        val cfg = config(replay)
        core = if (useDefaultFactory) {
            ARTrackerCore(app, cfg, clock, replaySink = sink)
        } else {
            ARTrackerCore(app, cfg, clock, replayFactory = { c, handler ->
                testReplayController(
                    c, handler, dispatcher, testScreenshotKit(c, handler, sink), { id, rate -> sampler(id, rate) },
                )
            }, replaySink = sink)
        }
        ARTrackerCore.install(core)
        core.start()
        if (foreground) ProcessLifecycleTestUtil.start()
        return core
    }

    private fun controller(): ReplayController = core.replayController!!

    /**
     * Names of the SDK-owned runnables still posted on the main handler (flush tick; frame tick; one-off capture).
     * Reads the private fields reflectively and asks the handler about each one, so unrelated posts never count.
     */
    private fun pendingSdkCallbacks(): List<String> {
        fun field(owner: Any, name: String): Any {
            val f = owner.javaClass.getDeclaredField(name)
            f.isAccessible = true
            return f.get(owner)!!
        }
        val controller = controller()
        val scheduler = field(controller, "scheduler")
        // hasCallbacks matches the posting Handler instance, so ask the very handlers the SDK posts through.
        val controllerHandler = field(controller, "mainHandler") as Handler
        val schedulerHandler = field(scheduler, "handler") as Handler
        return listOf(
            Triple("flush", controllerHandler, field(controller, "flushRunnable")),
            Triple("tick", schedulerHandler, field(scheduler, "tickRunnable")),
            Triple("soon", schedulerHandler, field(scheduler, "soonRunnable")),
        ).filter { (_, h, r) -> h.hasCallbacks(r as Runnable) }.map { it.first }
    }

    private fun idle(ms: Long = 0) {
        if (ms == 0L) shadowOf(looper).idle() else shadowOf(looper).idleFor(Duration.ofMillis(ms))
    }

    private fun launch(build: (Activity) -> View): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(build(activity))
        idle(50)
        return activity
    }

    private fun textActivity(text: String = "public text"): Pair<Activity, TextView> {
        lateinit var tv: TextView
        val activity = launch { a -> TextView(a).apply { this.text = text }.also { tv = it } }
        return activity to tv
    }

    /** Runs background work, then waits for the pipeline to hand every item to beforeSend. */
    private fun settle() {
        dispatcher.runAll()
        runBlocking { withTimeout(15_000) { core.awaitIdle() } }
    }

    /** Captures one frame now (main thread) and processes it. */
    private fun capture() {
        controller().captureFrame()
        dispatcher.runAll()
    }

    /** Closes the open chunk now and waits until it reached the sink. */
    private fun flushChunks() {
        controller().flushChunks()
        settle()
    }

    /** Lets the posted screen-change / session callbacks run (and the capture they request), then processes it. */
    private fun applyPosted() {
        idle(200)
        dispatcher.runAll()
    }

    private fun batches(): List<NewReplayBatch> = sinkBatches.toList()

    private fun chunk(batch: NewReplayBatch): JSONObject =
        JSONObject(GZIPInputStream(ByteArrayInputStream(batch.gzipBody)).use { String(it.readBytes(), Charsets.UTF_8) })

    private fun frameScreens(batch: NewReplayBatch): List<String> =
        chunk(batch).getJSONArray("frames").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("screen") } }

    /** The sanitized screen name of every frame that left the SDK, in order. */
    private fun screens() = batches().flatMap(::frameScreens)

    private fun frameCount() = screens().size

    private fun drawListenerCount(root: View): Int {
        val f = ViewTreeObserver::class.java.getDeclaredField("mOnDrawListeners")
        f.isAccessible = true
        val list = f.get(root.viewTreeObserver) as? java.util.List<*>
        return list?.size ?: 0
    }

    private fun touch(activity: Activity, action: Int, t: Long, x: Float = 100f, y: Float = 200f) {
        activity.window.callback.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))
    }

    @Test
    fun enabledAndSampled_emitsAScreenChunk() {
        startCore()
        textActivity()
        idle(1_000)
        dispatcher.runAll()
        clock.advance(10_000)
        idle(5_000)
        settle()

        val batch = batches().first()
        val doc = chunk(batch)
        assertEquals("artracker-screen-v1", doc.getString("format"))
        assertEquals("android", doc.getString("platform"))
        assertTrue(doc.getJSONArray("frames").length() >= 1)
        assertEquals(core.session.peekId(), batch.sessionId)
        assertEquals("test", batch.environment)
    }

    @Test
    fun emit_goesToSink_notToPipeline() {
        startCore()
        textActivity()
        capture()
        flushChunks()

        assertTrue(batches().isNotEmpty())
        assertTrue(seen.none { it.name == "replay_chunk" })
    }

    @Test
    fun flushTick_closesTheChunkOnlyAfterItsWindow() {
        startCore()
        textActivity()
        capture()
        idle(5_000) // flush tick: the chunk is not due yet
        settle()
        assertTrue(batches().isEmpty())

        clock.advance(5_000) // 5 s after the chunk opened: not due
        idle(5_000)
        settle()
        assertTrue(batches().isEmpty())

        clock.advance(5_000) // 10 s: due
        idle(5_000)
        settle()
        assertEquals(1, batches().size)
    }

    @Test
    fun halt_closesTheOpenChunk_onStop() {
        startCore()
        textActivity()
        capture()
        idle(5_000)
        settle()
        assertTrue(batches().isEmpty()) // the chunk is open and not due

        ARTracker.stopReplay()
        settle()

        assertEquals(1, batches().size)
    }

    @Test
    fun halt_closesTheOpenChunk_onAppBackground() {
        startCore()
        textActivity()
        capture()
        idle(5_000)
        settle()
        assertTrue(batches().isEmpty())

        ProcessLifecycleTestUtil.stop()
        settle()

        assertEquals(1, batches().size)
    }

    @Test
    fun sessionRotation_closesThePreviousChunk_beforeNewSessionFrames() {
        startCore()
        textActivity()
        capture()
        val first = core.session.peekId()!!
        ARTracker.reset()
        idle(200)
        capture() // first frame of the new session: the previous session's chunk is closed by then
        val second = core.session.peekId()!!
        assertTrue(first != second)

        assertEquals(listOf(first), batches().map { it.sessionId })
        flushChunks()
        assertEquals(listOf(first, second), batches().map { it.sessionId })
    }

    @Test
    fun disabled_discardsTheOpenChunk() {
        startCore()
        textActivity()
        capture()
        idle(5_000)
        settle()

        ARTracker.optOut(true)
        idle(200)
        settle()
        clock.advance(60_000)
        idle(10_000)
        settle()

        assertTrue(batches().isEmpty())
    }

    @Test
    fun noteError_flagsTheChunk() {
        startCore()
        textActivity()
        capture()
        controller().noteError()
        flushChunks()

        assertTrue(batches().single().hasError)
    }

    @Test
    fun disabled_emitsNothing() {
        startCore(ReplayConfig(enabled = false, sampleRate = 1.0))
        val (activity, _) = textActivity()
        idle(6_000)
        settle()

        assertTrue(batches().isEmpty())
        assertNull(core.replayController) // not even constructed
        assertSame(activity, activity.window.callback)
        assertEquals(0, drawListenerCount(activity.window.decorView))
    }

    @Test
    fun notSampled_emitsNothing() {
        sampler = { _, _ -> false }
        startCore()
        val (activity, _) = textActivity()
        idle(6_000)
        settle()

        assertTrue(batches().isEmpty())
        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
    }

    @Test
    fun realSampling_rateZero_emitsNothing() {
        startCore(ReplayConfig(enabled = true, sampleRate = 0.0))
        textActivity()
        idle(6_000)
        settle()
        assertTrue(batches().isEmpty())
    }

    @Test
    fun manualStart_ignoresSampling() {
        sampler = { _, _ -> false }
        startCore(ReplayConfig(enabled = false, sampleRate = 0.0))
        textActivity()
        ARTracker.startReplay()
        idle(1_000)
        dispatcher.runAll()
        capture()
        flushChunks()

        assertTrue(frameCount() >= 1)
    }

    @Test
    fun stopReplay_flushesAndRemovesEverything() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        assertTrue(activity.window.callback is TouchRecorder)

        ARTracker.stopReplay()
        settle()

        assertEquals(1, batches().size) // the open chunk was flushed
        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
        assertEquals(0, drawListenerCount(activity.window.decorView))
        idle(10_000)
        settle()
        assertEquals(1, batches().size)
    }

    @Test
    fun killSwitch_emitsNothing() {
        startCore()
        ARTracker.optOut(true)
        val (activity, _) = textActivity()
        idle(6_000)
        settle()
        assertTrue(batches().isEmpty())
        assertSame(activity, activity.window.callback)
    }

    @Test
    fun killSwitch_whileRecording_stopsAndDropsEverything() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        ARTracker.optOut(true)
        idle(6_000)
        settle()
        assertTrue(batches().isEmpty())
        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
    }

    @Test
    fun background_stopsCapture() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        assertEquals(1, drawListenerCount(activity.window.decorView))
        assertTrue("the probe must see the running controller's callbacks (running=${controller().isRunning})", pendingSdkCallbacks().isNotEmpty())

        ProcessLifecycleTestUtil.stop() // app_background
        settle()

        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
        assertEquals(0, drawListenerCount(activity.window.decorView))
        assertEquals(0, dispatcher.pending)
        // Only SDK-owned callbacks: the global looper queue also holds unrelated posts (WorkManager, Robolectric).
        assertEquals("pending SDK callbacks: ${pendingSdkCallbacks()}", emptyList<String>(), pendingSdkCallbacks())
        assertEquals(1, batches().size) // flushed on background
        assertEquals(1, frameCount())

        idle(130_000)
        settle()
        assertEquals(1, batches().size)

        // Foreground again: recording resumes with a first frame that is never suppressed as a duplicate.
        ProcessLifecycleTestUtil.start()
        applyPosted()
        capture()
        flushChunks()
        assertEquals(2, frameCount())
        assertTrue(activity.window.callback is TouchRecorder)
    }

    @Test
    fun anUnchangedScreen_emitsNoSecondFrame() {
        startCore()
        textActivity()
        capture()
        capture()
        flushChunks()
        assertEquals(1, frameCount())
    }

    @Test
    fun screenChange_forcesAFrame_evenWhenThePictureIsTheSame() {
        startCore()
        textActivity()
        capture()
        ARTracker.screen("Checkout")
        applyPosted() // the screen change requests a forced capture soon
        flushChunks()

        assertEquals(2, frameCount())
        assertEquals("android://Checkout", screens().last())
    }

    @Test
    fun checkout_every120s_forcesAFrame_evenWhenThePictureIsTheSame() {
        startCore()
        textActivity()
        capture()
        clock.advance(120_000)
        idle(5_000) // the flush tick sees the checkout interval is over and asks for a forced capture
        applyPosted() // ...which the scheduler runs soon after
        flushChunks()

        assertEquals(2, frameCount())
    }

    @Test
    fun newSession_startsANewChunk() {
        startCore()
        textActivity()
        capture()
        val first = core.session.peekId()!!
        ARTracker.reset() // rotates the session
        applyPosted()
        capture()
        flushChunks()

        val second = core.session.peekId()!!
        assertTrue(first != second)
        assertEquals(1, batches().count { it.sessionId == first })
        assertEquals(1, batches().count { it.sessionId == second })
    }

    @Test
    fun sessionNotSampled_afterRotation() {
        var firstOnly: String? = null
        sampler = { id, _ -> firstOnly == null || id == firstOnly }
        startCore()
        val (activity, _) = textActivity()
        capture()
        firstOnly = core.session.peekId()
        ARTracker.reset()
        idle(200)
        settle()

        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
        assertEquals(0, drawListenerCount(activity.window.decorView))
        assertEquals(listOf(firstOnly), batches().map { it.sessionId }) // only the flushed first-session chunk
        idle(10_000)
        settle()
        assertEquals(1, batches().size)
    }

    @Test
    fun touches_areRecordedInDp_onTheChunkOfTheFrames() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        val density = activity.resources.displayMetrics.density
        touch(activity, MotionEvent.ACTION_DOWN, 10, 100f, 200f)
        touch(activity, MotionEvent.ACTION_UP, 20, 100f, 200f)
        dispatcher.runAll()
        flushChunks()

        val touches = chunk(batches().single()).getJSONArray("touches")
        assertEquals(listOf("down", "up"), (0 until touches.length()).map { touches.getJSONObject(it).getString("kind") })
        assertEquals(Math.round(100f / density), touches.getJSONObject(0).getInt("x"))
        assertEquals(Math.round(200f / density), touches.getJSONObject(0).getInt("y"))
    }

    @Test
    fun rapidTapsOnOneSpot_flagTheChunkHasRageClick() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        repeat(3) {
            touch(activity, MotionEvent.ACTION_DOWN, 10, 100f, 200f)
            touch(activity, MotionEvent.ACTION_UP, 20, 100f, 200f)
            clock.advance(200)
        }
        dispatcher.runAll()
        flushChunks()

        assertTrue(batches().single().hasRageClick)
    }

    @Test
    fun aRageOnAStaticScreenWithNoOpenChunk_opensAChunkAtOnce_soTheFlagIsNotLost() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        // The 10 s timer closes the chunk (unlike flushChunks(), it keeps the duplicate filter): the screen is static,
        // so no later frame is sent and no chunk is open any more.
        clock.advance(10_000)
        idle(5_000)
        settle()
        assertEquals(1, batches().size)

        repeat(3) { // the burst completes on the third tap, with nothing open to carry the flag
            touch(activity, MotionEvent.ACTION_DOWN, 10, 100f, 200f)
            clock.advance(100)
        }
        dispatcher.runAll()
        applyPosted() // what the controller asked for: a frame of the unchanged screen, which opens a chunk
        flushChunks()

        assertEquals(2, batches().size)
        assertFalse(batches()[0].hasRageClick)
        assertTrue(batches()[1].hasRageClick)
    }

    @Test
    fun tapsFarApartInPixels_areComparedInDp() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        val density = activity.resources.displayMetrics.density
        touch(activity, MotionEvent.ACTION_DOWN, 10, 100f, 200f)
        clock.advance(100)
        // 25 dp from the first tap (in pixels: 25 * density): outside the 24 dp radius, so no burst of three.
        repeat(2) {
            touch(activity, MotionEvent.ACTION_DOWN, 10, 100f + 25 * density, 200f)
            clock.advance(100)
        }
        dispatcher.runAll()
        flushChunks()

        assertFalse(batches().single().hasRageClick)
    }

    @Test
    fun screenshotDisabled_stopsRecordingForTheProcess_andDropsTheOpenChunk() {
        startCore()
        val (activity, _) = textActivity()
        capture()

        controller().onScreenshotDisabled()
        settle()

        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
        assertEquals(0, drawListenerCount(activity.window.decorView))
        assertTrue(batches().isEmpty()) // dropped, not uploaded into another 403

        controller().onScreenshotDisabled() // a second 403 changes nothing
        ARTracker.startReplay() // not even a manual start brings it back
        ProcessLifecycleTestUtil.stop()
        ProcessLifecycleTestUtil.start()
        idle(10_000)
        settle()
        assertFalse(controller().isRunning)
        assertTrue(batches().isEmpty())
    }

    @Test
    fun composeAbsent_theDefaultFactoryStillBuildsAWorkingController() {
        ClassAvailability.overrides["androidx.compose.ui.platform.AndroidComposeView"] = false
        startCore(useDefaultFactory = true)
        textActivity()
        assertNotNull(core.replayController)
        assertTrue(controller().isRunning)
    }

    @Test
    fun composePresent_theDefaultFactoryStillBuildsAWorkingController() {
        ClassAvailability.overrides["androidx.compose.ui.platform.AndroidComposeView"] = true
        startCore(useDefaultFactory = true)
        textActivity()
        assertNotNull(core.replayController)
        assertTrue(controller().isRunning)
    }

    @Test
    fun startReplay_beforeInit_isNoOp_andLazilyCreatesController() {
        ARTracker.startReplay()
        ARTracker.stopReplay()
        startCore(ReplayConfig(enabled = false))
        assertNull(core.replayController)
        ARTracker.startReplay()
        assertNotNull(core.replayController)
        assertTrue(controller().isRunning)
    }

    @Test
    fun startReplay_fromBackgroundThread_isPostedToMain() {
        startCore(ReplayConfig(enabled = false))
        textActivity()
        val t = Thread { ARTracker.startReplay() }
        t.start()
        t.join()
        idle()
        assertTrue(controller().isRunning)
    }

    @Test
    fun href_isComputedOncePerScreenName_notOncePerFrame() {
        startCore()
        textActivity()
        val computed = ArrayList<String?>()
        controller().hrefBuilder = { name -> computed += name; ReplayHref.of(name) }

        ARTracker.screen("Profile")
        applyPosted()
        repeat(3) { capture() }
        assertEquals(listOf<String?>("Profile"), computed)

        ARTracker.screen("Settings")
        applyPosted()
        repeat(3) { capture() }
        assertEquals(listOf<String?>("Profile", "Settings"), computed)

        // Going back recomputes (only the LAST name is cached) and the output stays correct.
        ARTracker.screen("Profile")
        applyPosted()
        capture()
        assertEquals(listOf<String?>("Profile", "Settings", "Profile"), computed)
        flushChunks()
        assertEquals("android://Profile", screens().last())

        // A cached value is still the sanitized one (path ids masked), for every frame that reuses it.
        computed.clear()
        ARTracker.screen("order/12345")
        applyPosted()
        repeat(2) { capture() }
        assertEquals(listOf<String?>("order/12345"), computed)
        flushChunks()
        assertEquals("android://order/:id", screens().last())
    }

    @Test
    fun screenNameWithEmail_neverInChunk() {
        startCore()
        textActivity()
        ARTracker.screen("Profile john@doe.com")
        applyPosted()
        capture()
        flushChunks()

        assertTrue(screens().isNotEmpty())
        assertTrue(screens().none { it.contains("john") })
        assertEquals(1, screens().distinct().count { it.startsWith("android://Profile") })
    }

    @Test
    fun screenNameWithJwt_neverInChunk() {
        startCore()
        textActivity()
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.c2lnbmF0dXJlMTIz"
        ARTracker.screen("Detail $jwt")
        applyPosted()
        capture()
        flushChunks()

        assertTrue(screens().none { it.contains("eyJhbGciOiJIUzI1NiJ9") || it.contains("c2lnbmF0dXJlMTIz") })
        assertTrue(screens().last().startsWith("android://Detail"))
    }

    @Test
    fun plainScreenName_isKeptAsIs() {
        startCore()
        textActivity()
        ARTracker.screen("CheckoutActivity")
        applyPosted()
        capture()
        flushChunks()
        assertEquals("android://CheckoutActivity", screens().last())
    }

    @Test
    fun distinctNonAsciiScreens_withTheSameHref_stillGetAFrame() {
        startCore()
        textActivity()
        ARTracker.screen("首页")
        applyPosted()
        capture()
        flushChunks()
        val before = frameCount()

        ARTracker.screen("设置") // the same sanitized href, a different raw name: the change is still noticed
        applyPosted()
        capture()
        flushChunks()

        assertEquals(before + 1, frameCount())
        assertEquals(setOf("android://unknown"), screens().takeLast(2).toSet())
    }

    @Test
    fun routesDifferingOnlyInPathId_stillGetAFrame_andNoIdIsEmitted() {
        startCore()
        textActivity()
        ARTracker.screen("order/123456")
        applyPosted()
        capture()
        flushChunks()
        val before = frameCount()

        ARTracker.screen("order/987654")
        applyPosted()
        capture()
        flushChunks()

        assertEquals(before + 1, frameCount())
        assertEquals(listOf("android://order/:id", "android://order/:id"), screens().takeLast(2))
        assertTrue(screens().none { it.contains("123456") || it.contains("987654") })
    }

    @Test
    fun rawScreenName_neverInChunk() {
        startCore()
        textActivity()
        ARTracker.screen("ScreenKeyMarker-ä")
        applyPosted()
        capture()
        ARTracker.screen("ScreenKeyMarker-ö") // same sanitized href, different raw name
        applyPosted()
        capture()
        flushChunks()

        assertTrue(screens().none { it.contains("ScreenKeyMarker-ä") || it.contains("ScreenKeyMarker-ö") })
        assertTrue(screens().all { it == "android://ScreenKeyMarker-_" })
        assertEquals(2, frameCount())
    }
}
