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
import id.art.tracker.replay.capture.TriggerKind
import id.art.tracker.replay.screenshot.ScreenChunker
import id.art.tracker.replay.screenshot.ScreenshotKit
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
import org.junit.Assert.assertNotEquals
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

    /** Every call the sink received, in order: `accept:<session>:held=<b>`, `promote:<session>`, `discard:<session>`. */
    private val sinkEvents = CopyOnWriteArrayList<String>()
    private val sink = object : ReplaySink {
        override fun accept(batch: NewReplayBatch) {
            sinkBatches += batch
            sinkEvents += "accept:${batch.sessionId}:held=${batch.held}"
        }

        override fun promote(sessionId: String) {
            sinkEvents += "promote:$sessionId"
        }

        override fun discardHeld(sessionId: String) {
            sinkEvents += "discard:$sessionId"
        }
    }

    /** Only the on-error sampling hits: every session records in BUFFER mode. */
    private val onlyOnError = { id: String, _: Double -> id.endsWith(ON_ERROR_SALT) }
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

    /**
     * A started core whose replay controller runs its background work on [dispatcher]. The cold start tracker is off:
     * its real first-frame hook adds a draw listener of its own, and these tests count the controller's listeners.
     */
    private var imeVisible = false

    private fun startCore(
        replay: ReplayConfig = ReplayConfig(enabled = true, sampleRate = 1.0),
        useDefaultFactory: Boolean = false,
        foreground: Boolean = true,
    ): ARTrackerCore {
        val cfg = config(replay)
        core = if (useDefaultFactory) {
            ARTrackerCore(app, cfg, clock, replaySink = sink, appStartFactory = { _, _ -> null })
        } else {
            ARTrackerCore(app, cfg, clock, replayFactory = { c, handler ->
                testReplayController(
                    c, handler, dispatcher, testScreenshotKit(c, handler, sink), { id, rate -> sampler(id, rate) },
                    sink = sink, // the same sink as the chunker's: accept / promote / discardHeld arrive in order
                    imeVisibility = { imeVisible },
                )
            }, replaySink = sink, appStartFactory = { _, _ -> null })
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

    /** The core owns the input wrapper while an Activity is resumed; replay must leave no listener on it. */
    private fun assertReplayNotBound(activity: Activity) {
        val callback = activity.window.callback
        if (callback !== activity) {
            assertTrue("unexpected callback $callback", callback is TouchRecorder)
            assertNull((callback as TouchRecorder).onTouch)
            assertNull(callback.onFocusChanged)
        }
    }

    /** Moves the fake clock and the main looper together, in 1 s steps, running the background work as it goes. */
    private fun advanceBoth(ms: Long, stepMs: Long = 1_000, each: () -> Unit = {}) {
        var left = ms
        while (left > 0) {
            val step = minOf(stepMs, left)
            clock.advance(step)
            idle(step)
            dispatcher.runAll()
            each()
            left -= step
        }
    }

    private fun schedulerField(name: String): Any? {
        val scheduler = controller().javaClass.getDeclaredField("scheduler").also { it.isAccessible = true }.get(controller())!!
        return scheduler.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(scheduler)
    }

    /** Simulates a draw of the root (the real draw listener only flips this flag). */
    private fun markDirty() {
        val scheduler = controller().javaClass.getDeclaredField("scheduler").also { it.isAccessible = true }.get(controller())!!
        scheduler.javaClass.getDeclaredMethod("markDirty").invoke(scheduler)
    }

    /** Walks a freshly started controller (one frame taken) into DORMANT: no input for 3 minutes. */
    private var dormantView: TextView? = null

    private fun startDormant(): Activity {
        startCore()
        val (activity, tv) = textActivity()
        dormantView = tv
        capture()
        advanceBoth(181_000)
        flushChunks()
        return activity
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
        idle(500) // longer than the 300 ms debounce of a screen-change trigger
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
    fun onError_flagsTheChunk() {
        startCore()
        textActivity()
        capture()
        controller().onError()
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
        assertReplayNotBound(activity)
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
        assertReplayNotBound(activity)
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
        assertReplayNotBound(activity)
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
        assertReplayNotBound(activity)
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
        assertReplayNotBound(activity)
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
        assertReplayNotBound(activity)
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
    fun shortBackground_resumesRecordingUnderSameSession() {
        startCore()
        textActivity()
        capture()
        val first = core.session.peekId()!!

        ProcessLifecycleTestUtil.stop()
        settle()
        clock.advance(60_000) // well inside the 30 minute timeout
        ProcessLifecycleTestUtil.start()
        applyPosted()
        assertTrue(controller().isRunning)
        capture()
        flushChunks()

        assertEquals(first, core.session.peekId())
        assertEquals(2, batches().size) // one closed on background, one after the return
        assertTrue(batches().all { it.sessionId == first })
    }

    @Test
    fun longBackground_resumesRecordingUnderNewSession() {
        startCore()
        textActivity()
        capture()
        val first = core.session.peekId()!!

        ProcessLifecycleTestUtil.stop()
        settle()
        clock.advance(1_800_001) // just past the 30 minute timeout
        ProcessLifecycleTestUtil.start()
        applyPosted()
        capture()
        flushChunks()

        val second = core.session.peekId()!!
        assertNotEquals(first, second)
        assertEquals(listOf(first, second), batches().map { it.sessionId })
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
        val (activity, _) = textActivity()
        capture()
        repeat(5) { // a touch every 24 s keeps the user ACTIVE (the keyframe only runs while ACTIVE)
            advanceBoth(24_000)
            touch(activity, MotionEvent.ACTION_DOWN, 10)
        }
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
        assertReplayNotBound(activity)
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
        assertReplayNotBound(activity)
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

    // region capture lifecycle: Active -> Idle -> Dormant

    @Test
    fun idleThenDormant_stopsPeriodicCaptureAndClosesChunk() {
        startCore()
        textActivity()
        capture()
        advanceBoth(29_000)
        assertEquals(1.0, schedulerField("stateRate") as Double, 0.0) // still ACTIVE: the configured rate
        advanceBoth(2_000)
        assertEquals(0.2, schedulerField("stateRate") as Double, 0.0) // IDLE: one check every 5 s

        advanceBoth(139_000) // t = 170 s: an important change opens a chunk, which DORMANT will close on entry
        controller().onTrigger(TriggerKind.SCREEN)
        advanceBoth(1_000)
        val before = batches().size
        advanceBoth(8_000) // t = 179 s: chunk still open and not due
        assertEquals(before, batches().size)
        advanceBoth(2_000) // t = 181 s: DORMANT entered, the chunk was closed at once
        settle()
        assertEquals(before + 1, batches().size)
        assertEquals(true, schedulerField("statePaused"))
        assertFalse("flush" in pendingSdkCallbacks())

        val frames = frameCount()
        advanceBoth(60_000) { markDirty() }
        flushChunks()
        assertEquals(frames, frameCount())
    }

    @Test
    fun carouselIdle_stopsCapturingAfterDormant() {
        startCore()
        val (_, tv) = textActivity("a")
        var n = 0
        lateinit var animate: Runnable
        animate = Runnable {
            tv.text = if (n++ % 2 == 0) "banner A" else "banner B"
            markDirty()
            tv.postDelayed(animate, 100)
        }
        tv.post(animate)
        advanceBoth(181_000)
        flushChunks()
        val atDormancy = frameCount()
        advanceBoth(1_800_000 - 181_000, stepMs = 5_000)
        flushChunks()

        assertEquals("no frame may be taken once DORMANT without a trigger", atDormancy, frameCount())
        assertTrue("frames=$atDormancy", atDormancy in 1..(30 * 1 + 150 * 1 / 5 + 2))
    }

    @Test
    fun dormantTouchDown_capturesImmediatelyAndResumes() {
        val activity = startDormant()
        val frames = frameCount()

        touch(activity, MotionEvent.ACTION_DOWN, 10)
        idle(0) // no debounce: the frame of what the user came back to is taken now
        dispatcher.runAll()
        flushChunks()

        assertEquals(frames + 1, frameCount())
        assertEquals(1.0, schedulerField("stateRate") as Double, 0.0) // back to the configured rate
        assertEquals(false, schedulerField("statePaused"))
        assertTrue("flush" in pendingSdkCallbacks())
    }

    @Test
    fun dormantScreenChange_capturesOnceStaysDormant() {
        startDormant()
        val frames = frameCount()

        ARTracker.screen("Checkout")
        idle(200)
        dispatcher.runAll()
        flushChunks()
        assertEquals(frames, frameCount()) // inside the 300 ms debounce
        idle(200)
        dispatcher.runAll()
        flushChunks()

        assertEquals(frames + 1, frameCount())
        assertEquals(true, schedulerField("statePaused"))
        advanceBoth(60_000) { markDirty() }
        flushChunks()
        assertEquals(frames + 1, frameCount())
    }

    @Test
    fun pollingApiInDormant_isRateLimited() {
        startDormant()
        val frames = frameCount()

        repeat(3) { // three responses within 2 s coalesce into one capture
            dormantView!!.text = "result ${clock.nowMs()}"
            controller().onTrigger(TriggerKind.NETWORK)
            advanceBoth(700)
        }
        advanceBoth(1_000)
        flushChunks()
        assertEquals(frames + 1, frameCount())

        advanceBoth(10_000) // the limiter window passed
        val polled = frameCount()
        repeat(20) { // every 3 s for 60 s, each response changes what is on screen
            dormantView!!.text = "poll ${clock.nowMs()}"
            controller().onTrigger(TriggerKind.NETWORK)
            advanceBoth(3_000)
        }
        flushChunks()
        assertTrue("captures=${frameCount() - polled}", frameCount() - polled in 1..6)
    }

    @Test
    fun dormantError_capturesAndFlagsChunk() {
        startDormant()
        val frames = frameCount()
        val batchesBefore = batches().size

        controller().onError()
        controller().onTrigger(TriggerKind.ERROR)
        advanceBoth(1_000)
        assertEquals(batchesBefore, batches().size) // the chunk is open
        advanceBoth(10_000) // the one-shot close 10 s after the trigger
        settle()

        assertEquals(batchesBefore + 1, batches().size)
        assertTrue(batches().last().hasError)
        assertEquals(frames + 1, frameCount())
    }

    @Test
    fun dormantNewWindow_capturesOnce() {
        startDormant()
        val frames = frameCount()

        dormantView!!.text = "a new window changed the picture"
        controller().onTrigger(TriggerKind.WINDOW)
        advanceBoth(1_000)
        flushChunks()

        assertEquals(frames + 1, frameCount())
    }

    @Test
    fun expiredSession_triggersIgnored() {
        startDormant()
        advanceBoth(31 * 60_000L, stepMs = 60_000)
        assertTrue(core.session.isExpired())
        flushChunks()
        val frames = frameCount()

        controller().onTrigger(TriggerKind.NETWORK)
        controller().onTrigger(TriggerKind.SCREEN)
        advanceBoth(2_000)
        flushChunks()

        assertEquals(frames, frameCount())
    }

    @Test
    fun wakeAfterTimeout_frameBelongsToNewSession() {
        val activity = startDormant()
        val old = core.session.peekId()!!
        advanceBoth(31 * 60_000L, stepMs = 60_000)

        touch(activity, MotionEvent.ACTION_DOWN, 10) // the core rotates the session first, then replay wakes
        idle(0)
        dispatcher.runAll()
        flushChunks()

        val fresh = core.session.peekId()!!
        assertTrue(old != fresh)
        assertEquals(fresh, batches().last().sessionId)
        assertTrue(batches().count { it.sessionId == old } >= 1)
    }

    @Test
    fun dormant10min_touchKeepsSession() {
        val activity = startDormant()
        val id = core.session.peekId()
        advanceBoth(10 * 60_000L, stepMs = 10_000)

        touch(activity, MotionEvent.ACTION_DOWN, 10)

        assertEquals(id, core.session.currentId())
    }

    @Test
    fun keyframeOnlyInActive_notWhileIdle() {
        startCore()
        textActivity()
        capture()
        advanceBoth(125_000) // IDLE then DORMANT: the 120 s keyframe must not fire
        flushChunks()

        assertEquals(1, frameCount())
    }

    @Test
    fun dialogShownInDormant_capturesOnce() {
        val activity = startDormant()
        val frames = frameCount()

        android.app.AlertDialog.Builder(activity).setMessage("Session expiring").show()
        // A device tells the activity window it lost focus to the dialog; that is the cue to look again.
        activity.window.callback.onWindowFocusChanged(false)
        idle(200)
        dispatcher.runAll()
        flushChunks()
        assertEquals(frames, frameCount()) // inside the 300 ms debounce
        idle(200)
        dispatcher.runAll()
        flushChunks()
        assertEquals(frames + 1, frameCount())

        advanceBoth(5_000) // the capture found the dialog's window itself: no second capture for the same dialog
        flushChunks()
        assertEquals(frames + 1, frameCount())
        assertEquals(true, schedulerField("statePaused"))
    }

    @Test
    fun manualCapture_inDormant_immediate() {
        startDormant()
        val frames = frameCount()

        ARTracker.captureReplayFrame("checkout summary shown")
        idle(0)
        dispatcher.runAll()
        flushChunks()

        assertEquals(frames + 1, frameCount())
        assertEquals(true, schedulerField("statePaused")) // a manual capture never wakes the lifecycle
    }

    @Test
    fun customIdleThresholds_areUsed() {
        startCore(ReplayConfig(enabled = true, sampleRate = 1.0, idleAfterMs = 5_000, dormantAfterMs = 8_000))
        textActivity()
        capture()
        advanceBoth(6_000)
        assertEquals(0.2, schedulerField("stateRate") as Double, 0.0)
        advanceBoth(3_000)
        assertEquals(true, schedulerField("statePaused"))
    }

    @Test
    fun unchangedScreenPolling_inDormant_sendsNoDuplicateFrames() {
        startDormant()
        controller().onTrigger(TriggerKind.NETWORK) // the first trigger frame after the test's own chunk flush is new
        advanceBoth(12_000) // ...and its chunk closes by the one-shot flush, which keeps the duplicate filter
        settle()
        val frames = frameCount()
        val batchesBefore = batches().size

        repeat(30) { // an API polled every 3 s whose result does not change the picture
            controller().onTrigger(TriggerKind.NETWORK)
            advanceBoth(3_000)
        }
        advanceBoth(12_000)
        settle()

        assertEquals(frames, frameCount())
        assertEquals(batchesBefore, batches().size)
    }

    @Test
    fun repeatedErrors_inDormant_areRateLimited_andTheChunkStillCloses() {
        startDormant()
        val frames = frameCount()
        val batchesBefore = batches().size

        repeat(10) { // a failing request every 2 s for 20 s
            controller().onError()
            controller().onTrigger(TriggerKind.ERROR)
            advanceBoth(2_000)
        }
        advanceBoth(11_000) // the one-shot close fired although triggers kept coming
        settle()

        assertTrue(batches().size > batchesBefore)
        val taken = frameCount() - frames
        assertTrue("taken=$taken", taken in 1..3)
    }

    @Test
    fun lateInit_noActivityObserved_staysActiveAndKeepsCapturing() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get() // resumed before the SDK starts
        activity.setContentView(TextView(activity).apply { text = "late" })
        idle(50)
        startCore()
        idle(200)
        dispatcher.runAll()

        advanceBoth(200_000)
        assertEquals(1.0, schedulerField("stateRate") as Double, 0.0) // no input can be seen: never idle
        advanceBoth(31 * 60_000L, stepMs = 60_000)
        flushChunks()
        val frames = frameCount()
        controller().onTrigger(TriggerKind.SCREEN)
        advanceBoth(2_000)
        flushChunks()
        assertEquals(frames + 1, frameCount()) // the (unobservable) session is not treated as expired
    }

    @Test
    fun anExtraWindowOnScreen_keepsCaptureActive() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        android.app.AlertDialog.Builder(activity).setMessage("Choose a plan").show()
        advanceBoth(2_000) // a capture finds the dialog's window
        advanceBoth(200_000) // the user scrolls inside it: input the activity window never sees

        assertEquals(1.0, schedulerField("stateRate") as Double, 0.0)
        assertEquals(false, schedulerField("statePaused"))
    }

    private fun layoutPass(view: View) {
        val m = ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnGlobalLayout")
        m.isAccessible = true
        m.invoke(view.viewTreeObserver)
    }

    @Test
    fun softKeyboardVisible_keepsCaptureActive_untilItIsHidden() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        imeVisible = true // typing in a form: the keyboard is another process, the app window sees no touch
        layoutPass(activity.window.decorView)
        advanceBoth(200_000)
        assertEquals(1.0, schedulerField("stateRate") as Double, 0.0)
        assertEquals(false, schedulerField("statePaused"))

        imeVisible = false
        layoutPass(activity.window.decorView)
        advanceBoth(185_000)
        assertEquals(true, schedulerField("statePaused")) // away again: back to the normal lifecycle
    }

    @Test
    fun hardwareKeyInDormant_wakesCapture() {
        val activity = startDormant()
        val frames = frameCount()

        activity.window.callback.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        idle(0)
        dispatcher.runAll()
        flushChunks()

        assertEquals(frames + 1, frameCount())
        assertEquals(1.0, schedulerField("stateRate") as Double, 0.0)
    }

    // endregion

    // region BUFFER mode (replay on error)

    private fun chunkerOf(): ScreenChunker {
        val kit = controller().javaClass.getDeclaredField("screenshot").also { it.isAccessible = true }
            .get(controller()) as ScreenshotKit
        return kit.chunker
    }

    /** A BUFFER-mode core: only the on-error sampling hits. */
    private fun startBuffer(): TextView {
        sampler = onlyOnError
        startCore(ReplayConfig(enabled = true))
        val (_, tv) = textActivity()
        dispatcher.runAll()
        return tv
    }

    /** Changes the picture and captures it, so every call yields a frame (dedup lets it through). */
    private var frameNo = 0
    private fun changeAndCapture(tv: TextView) {
        tv.text = "frame ${frameNo++}"
        idle(0)
        capture()
    }

    @Test
    fun bufferMode_chunksAreHeld_andUse5sWindow() {
        val tv = startBuffer()
        assertEquals(RecordingMode.BUFFER, controller().mode)
        changeAndCapture(tv)
        advanceBoth(6_000) { changeAndCapture(tv) }
        settle()

        // A 10 s window would still hold the chunk open: it closed within 6 s.
        assertTrue("batches=${batches().size}", batches().isNotEmpty())
        assertTrue(batches().all { it.held })
        assertEquals(ScreenChunker.BUFFER_WINDOW_MS, chunkerOf().windowMs)
    }

    @Test
    fun bufferMode_noError_nothingPromoted() {
        val tv = startBuffer()
        changeAndCapture(tv)
        advanceBoth(120_000) { changeAndCapture(tv) }
        flushChunks()

        assertTrue(batches().size >= 2)
        assertTrue(batches().all { it.held })
        assertTrue(sinkEvents.none { it.startsWith("promote:") })
    }

    @Test
    fun bufferMode_error_closesOpenChunkHeld_thenPromotes() {
        val tv = startBuffer()
        changeAndCapture(tv)
        val sid = core.session.peekId()!!
        assertTrue(batches().isEmpty()) // the chunk is open

        controller().onError()
        dispatcher.runAll()

        assertEquals(listOf("accept:$sid:held=true", "promote:$sid"), sinkEvents.toList())
        assertTrue(batches().single().hasError)
    }

    @Test
    fun bufferMode_followUp30s_ready_thenHeldAgain() {
        val tv = startBuffer()
        changeAndCapture(tv)
        controller().onError()
        dispatcher.runAll()
        val atError = batches().size

        advanceBoth(25_000) { changeAndCapture(tv) } // inside the 30 s follow-up window
        val inFollowUp = batches().drop(atError)
        assertTrue("inFollowUp=${inFollowUp.size}", inFollowUp.isNotEmpty())
        assertTrue(inFollowUp.none { it.held })

        advanceBoth(10_000) { changeAndCapture(tv) } // the window ends here: its chunk ships, held again after
        val afterEnd = batches().size
        advanceBoth(20_000) { changeAndCapture(tv) }
        flushChunks()

        val later = batches().drop(afterEnd)
        assertTrue("later=${later.size}", later.isNotEmpty())
        assertTrue(later.all { it.held })
        assertEquals(1, sinkEvents.count { it.startsWith("promote:") })
    }

    @Test
    fun errorBurst_promotesOnce() {
        val tv = startBuffer()
        changeAndCapture(tv)
        repeat(10) { // an API failing every 500 ms
            controller().onError()
            advanceBoth(500) { changeAndCapture(tv) }
        }
        settle()

        assertEquals(1, sinkEvents.count { it.startsWith("promote:") })
    }

    @Test
    fun bufferMode_sessionChange_discardsOldHeld() {
        val tv = startBuffer()
        changeAndCapture(tv)
        val first = core.session.peekId()!!

        ARTracker.reset() // rotates the session
        idle(200)
        dispatcher.runAll()
        val second = core.session.peekId()!!
        assertNotEquals(first, second)

        // The old chunk is closed held, then the old session's held chunks are dropped.
        assertEquals(listOf("accept:$first:held=true", "discard:$first"), sinkEvents.toList())
        assertEquals(RecordingMode.BUFFER, controller().mode)

        changeAndCapture(tv)
        flushChunks()
        assertEquals("accept:$second:held=true", sinkEvents.last())
    }

    @Test
    fun bufferMode_sessionChangeToNotRecorded_discardsOldHeld() {
        var firstOnly: String? = null
        sampler = { id, rate -> (firstOnly == null || id.startsWith(firstOnly!!)) && onlyOnError(id, rate) }
        startCore(ReplayConfig(enabled = true))
        val (_, tv) = textActivity()
        changeAndCapture(tv)
        val first = core.session.peekId()!!
        firstOnly = first

        ARTracker.reset()
        idle(200)
        settle()

        assertFalse(controller().isRunning)
        assertNull(controller().mode)
        assertEquals(listOf("accept:$first:held=true", "discard:$first"), sinkEvents.toList())
    }

    /** BUFFER session, one held chunk closed by the background, then the session times out in the background. */
    private fun bufferThenBackgroundTimeout(): String {
        val tv = startBuffer()
        changeAndCapture(tv)
        val old = core.session.peekId()!!
        ProcessLifecycleTestUtil.stop()
        settle()
        assertEquals(listOf("accept:$old:held=true"), sinkEvents.toList())
        clock.advance(1_800_001) // just past the 30 minute timeout
        return old
    }

    @Test
    fun bufferMode_backgroundTimeout_newSession_discardsOldHeld() {
        val old = bufferThenBackgroundTimeout()

        ProcessLifecycleTestUtil.start() // the core rotates the session, then replay re-evaluates
        applyPosted()
        settle()
        val fresh = core.session.peekId()!!
        assertNotEquals(old, fresh)
        assertTrue(controller().isRunning)
        assertEquals(RecordingMode.BUFFER, controller().mode)
        assertEquals(listOf("accept:$old:held=true", "discard:$old"), sinkEvents.toList())

        controller().onDisabledChanged() // a later re-evaluate does not discard again
        applyPosted()
        assertEquals(1, sinkEvents.count { it == "discard:$old" })
    }

    @Test
    fun bufferMode_backgroundTimeout_newSessionNotRecorded_discardsOldHeld() {
        var recordOnly: String? = null
        sampler = { id, rate -> (recordOnly == null || id.startsWith(recordOnly!!)) && onlyOnError(id, rate) }
        startCore(ReplayConfig(enabled = true))
        val (_, tv) = textActivity()
        changeAndCapture(tv)
        val old = core.session.peekId()!!
        recordOnly = old
        ProcessLifecycleTestUtil.stop()
        settle()
        clock.advance(1_800_001)

        ProcessLifecycleTestUtil.start()
        applyPosted()
        settle()
        assertNotEquals(old, core.session.peekId())
        assertFalse(controller().isRunning)
        assertEquals(listOf("accept:$old:held=true", "discard:$old"), sinkEvents.toList())

        controller().onDisabledChanged() // a later re-evaluate does not discard again
        applyPosted()
        assertEquals(1, sinkEvents.count { it == "discard:$old" })
    }

    @Test
    fun fullMode_unchanged_noPromoteNoHeld() {
        sampler = { _, _ -> true }
        startCore()
        val (_, tv) = textActivity()
        assertEquals(RecordingMode.FULL, controller().mode)
        changeAndCapture(tv)
        controller().onError()
        advanceBoth(15_000) { changeAndCapture(tv) }
        controller().onError()
        flushChunks()

        assertTrue(batches().isNotEmpty())
        assertTrue(batches().none { it.held })
        assertTrue(batches().first().hasError)
        assertTrue(sinkEvents.none { it.startsWith("promote:") || it.startsWith("discard:") })
        assertEquals(ScreenChunker.DEFAULT_WINDOW_MS, chunkerOf().windowMs)
    }

    @Test
    fun manualStart_isFull_evenWithBufferSampler() {
        val tv = startBuffer()
        assertEquals(RecordingMode.BUFFER, controller().mode)
        changeAndCapture(tv)
        val sid = core.session.peekId()!!

        ARTracker.startReplay()
        idle(200)
        dispatcher.runAll()
        assertEquals(RecordingMode.FULL, controller().mode)
        // Same session, BUFFER -> FULL: the buffered chunk closes held and the session's held chunks are promoted.
        assertEquals(listOf("accept:$sid:held=true", "promote:$sid"), sinkEvents.toList())

        val before = batches().size
        changeAndCapture(tv)
        flushChunks()
        val after = batches().drop(before)
        assertTrue(after.isNotEmpty())
        assertTrue(after.none { it.held })
        assertEquals(ScreenChunker.DEFAULT_WINDOW_MS, chunkerOf().windowMs)
    }

    @Test
    fun bufferMode_background_closesHeld_noKick() {
        val tv = startBuffer()
        changeAndCapture(tv)

        ProcessLifecycleTestUtil.stop()
        settle()

        assertTrue(batches().last().held)
        assertTrue(sinkEvents.none { it.startsWith("promote:") })
        assertNull(controller().mode)
    }

    // endregion
}
