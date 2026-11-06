package id.art.tracker.replay

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.BeforeSend
import id.art.tracker.ItemType
import id.art.tracker.ReplayConfig
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.capture.ComposeWalkerFactory
import id.art.tracker.replay.capture.ViewWalker
import id.art.tracker.replay.touch.TouchRecorder
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.toJson
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

@Suppress("UNCHECKED_CAST")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayControllerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val looper: Looper = Looper.getMainLooper()
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
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
            ARTrackerCore(app, cfg, clock)
        } else {
            ARTrackerCore(app, cfg, clock, replayFactory = { c, handler ->
                ReplayController.create(c, ViewWalker(MaskingPolicy(c.config.privacy)), handler, { id, rate -> sampler(id, rate) }, dispatcher)
            })
        }
        ARTrackerCore.install(core)
        core.start()
        if (foreground) ProcessLifecycleTestUtil.start()
        return core
    }

    private fun controller(): ReplayController = core.replayController!!

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

    /** Closes the open chunk and waits until it reached the pipeline. */
    private fun flushChunks() {
        controller().flushChunks()
        settle()
    }

    private fun chunks(): List<TelemetryItem> = seen.filter { it.name == "replay_chunk" }

    private fun events(item: TelemetryItem) = item.attributes["events"] as List<Map<String, Any?>>

    private fun allEvents() = chunks().flatMap(::events)

    private fun fullSnapshots() = allEvents().count { it["type"] == 2 }

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
    fun enabledAndSampled_emitsFullSnapshotChunk() {
        startCore()
        textActivity()
        idle(1_000)
        dispatcher.runAll()
        idle(5_000)
        settle()

        val chunk = chunks().first()
        assertEquals(ItemType.SESSION, chunk.type)
        assertEquals(true, chunk.attributes["full_snapshot"])
        assertEquals("android", chunk.attributes["platform"])
        assertEquals(0, (chunk.attributes["seq"] as Number).toInt())
        assertEquals(listOf(4, 2), events(chunk).take(2).map { it["type"] })
        assertTrue(chunk.toJson().contains("public text"))
        assertEquals(core.session.peekId(), chunk.sessionId)
    }

    @Test
    fun disabled_emitsNothing() {
        startCore(ReplayConfig(enabled = false, sampleRate = 1.0))
        val (activity, _) = textActivity()
        idle(6_000)
        settle()

        assertTrue(chunks().isEmpty())
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

        assertTrue(chunks().isEmpty())
        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
    }

    @Test
    fun realSampling_rateZero_emitsNothing() {
        startCore(ReplayConfig(enabled = true, sampleRate = 0.0))
        textActivity()
        idle(6_000)
        settle()
        assertTrue(chunks().isEmpty())
    }

    @Test
    fun manualStart_ignoresSampling() {
        sampler = { _, _ -> false }
        startCore(ReplayConfig(enabled = false, sampleRate = 0.0))
        textActivity()
        ARTracker.startReplay()
        idle(1_000)
        dispatcher.runAll()
        idle(5_000)
        settle()

        assertTrue(chunks().any { it.attributes["full_snapshot"] == true })
    }

    @Test
    fun stopReplay_flushesAndRemovesEverything() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        assertTrue(activity.window.callback is TouchRecorder)

        ARTracker.stopReplay()
        settle()

        assertEquals(1, chunks().size) // the open chunk was flushed
        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
        assertEquals(0, drawListenerCount(activity.window.decorView))
        idle(10_000)
        settle()
        assertEquals(1, chunks().size)
    }

    @Test
    fun killSwitch_emitsNothing() {
        startCore()
        ARTracker.optOut(true)
        val (activity, _) = textActivity()
        idle(6_000)
        settle()
        assertTrue(chunks().isEmpty())
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
        assertTrue(chunks().isEmpty())
        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
    }

    @Test
    fun background_stopsCapture() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        assertEquals(1, drawListenerCount(activity.window.decorView))

        ProcessLifecycleTestUtil.stop() // app_background
        settle()

        assertFalse(controller().isRunning)
        assertSame(activity, activity.window.callback)
        assertEquals(0, drawListenerCount(activity.window.decorView))
        assertEquals(0, dispatcher.pending)
        assertEquals(Duration.ZERO, shadowOf(looper).nextScheduledTaskTime)
        assertEquals(1, chunks().size) // flushed on background
        assertEquals(true, chunks().single().attributes["full_snapshot"])

        idle(130_000)
        settle()
        assertEquals(1, chunks().size)

        // Foreground again: recording resumes with a fresh full snapshot.
        ProcessLifecycleTestUtil.start()
        idle(200)
        dispatcher.runAll()
        flushChunks()
        assertEquals(2, fullSnapshots())
        assertTrue(activity.window.callback is TouchRecorder)
    }

    @Test
    fun passwordNeverInChunk() {
        startCore()
        launch { a ->
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(EditText(a).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText("hunter2")
                })
                addView(EditText(a).apply { setText("my-secret-note") })
                addView(TextView(a).apply { text = "public text" })
            }
        }
        capture()
        flushChunks()

        val json = chunks().joinToString("\n") { it.toJson() }
        assertTrue(json.contains("public text"))
        assertFalse(json.contains("hunter2"))
        assertFalse(json.contains("my-secret-note"))
    }

    @Test
    fun mutationAfterTextChange() {
        startCore()
        val (_, tv) = textActivity("before")
        capture()
        tv.text = "after!"
        capture()
        flushChunks()

        val events = allEvents()
        assertEquals(1, events.count { it["type"] == 2 })
        val mutation = events.last()
        assertEquals(3, mutation["type"])
        val texts = (mutation["data"] as Map<String, Any?>)["texts"] as List<Map<String, Any?>>
        assertEquals(listOf("after!"), texts.map { it["value"] })
    }

    @Test
    fun unchangedFrame_emitsNoEvent() {
        startCore()
        textActivity()
        capture()
        capture()
        flushChunks()
        assertEquals(listOf(4, 2), allEvents().map { it["type"] })
    }

    @Test
    fun screenChange_forcesFullSnapshot() {
        startCore()
        textActivity()
        capture()
        ARTracker.screen("Checkout")
        idle(200) // the screen change requests a capture soon
        dispatcher.runAll()
        flushChunks()

        assertEquals(2, fullSnapshots())
        val metas = allEvents().filter { it["type"] == 4 }.map { (it["data"] as Map<String, Any?>)["href"] }
        assertEquals("android://Checkout", metas.last())
        // The snapshot sequence opened a new chunk.
        assertTrue(chunks().last().attributes["full_snapshot"] == true)
        assertEquals(2, chunks().size)
    }

    @Test
    fun droppedFrame_forcesFullSnapshot() {
        startCore()
        val (_, tv) = textActivity("one")
        capture()
        tv.text = "two"
        controller().captureFrame() // not processed yet...
        tv.text = "six"
        controller().captureFrame() // ...so the previous frame is dropped
        dispatcher.runAll()
        flushChunks()

        val events = allEvents()
        assertEquals(2, events.count { it["type"] == 2 })
        assertTrue(events.none { it["type"] == 3 && (it["data"] as Map<String, Any?>)["source"] == 0 })
    }

    @Test
    fun checkout_every120s_forcesFullSnapshot() {
        startCore()
        val (_, tv) = textActivity("one")
        capture()
        tv.text = "two"
        clock.advance(60_000)
        capture()
        tv.text = "six"
        clock.advance(60_000)
        capture()
        flushChunks()

        assertEquals(listOf(4, 2, 3, 4, 2), allEvents().map { it["type"] })
    }

    @Test
    fun newSession_forcesFullSnapshot() {
        startCore()
        textActivity()
        capture()
        val first = core.session.peekId()!!
        ARTracker.reset() // rotates the session
        idle(200)
        dispatcher.runAll()
        flushChunks()

        val second = core.session.peekId()!!
        assertTrue(first != second)
        val old = chunks().filter { it.sessionId == first }
        val new = chunks().filter { it.sessionId == second }
        assertEquals(1, old.size)
        assertEquals(1, new.size)
        assertEquals(true, new.single().attributes["full_snapshot"])
        assertEquals(listOf(4, 2), events(new.single()).map { it["type"] })
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
        assertEquals(listOf(firstOnly), chunks().map { it.sessionId }) // only the flushed first-session chunk
        idle(10_000)
        settle()
        assertEquals(1, chunks().size)
    }

    @Test
    fun touches_areRecordedInDp_andUpRequestsCapture() {
        startCore()
        val (activity, _) = textActivity()
        capture()
        val density = activity.resources.displayMetrics.density
        touch(activity, MotionEvent.ACTION_DOWN, 10, 100f, 200f)
        touch(activity, MotionEvent.ACTION_UP, 20, 100f, 200f)
        dispatcher.runAll()
        flushChunks()

        val touches = allEvents().filter { it["type"] == 3 && (it["data"] as Map<String, Any?>)["source"] == 2 }
        assertEquals(listOf(7, 9), touches.map { (it["data"] as Map<String, Any?>)["type"] })
        val data = touches.first()["data"] as Map<String, Any?>
        assertEquals(Math.round(100f / density), data["x"])
        assertEquals(Math.round(200f / density), data["y"])
    }

    @Test
    fun composeAbsent_noComposeClassLoading() {
        ClassAvailability.overrides["androidx.compose.ui.platform.AndroidComposeView"] = false
        val before = ComposeWalkerFactory.createCalls.get()
        startCore(useDefaultFactory = true)
        textActivity()
        assertNotNull(core.replayController)
        assertEquals(before, ComposeWalkerFactory.createCalls.get())
    }

    @Test
    fun composePresent_composeWalkerCreated() {
        ClassAvailability.overrides["androidx.compose.ui.platform.AndroidComposeView"] = true
        val before = ComposeWalkerFactory.createCalls.get()
        startCore(useDefaultFactory = true)
        assertEquals(before + 1, ComposeWalkerFactory.createCalls.get())
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
}
