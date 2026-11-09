package id.art.tracker.replay

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.BeforeSend
import id.art.tracker.ReplayConfig
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.core.Json
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.capture.ViewWalker
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.transport.NewReplayBatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/**
 * Produces a realistic, multi-frame replay with the real walker, encoder, chunker and controller (a Login screen
 * with a masked password, a text change, an added and a removed view, taps, then a Feed screen) and writes it to
 * `build/replay-export/` so `tools/replay-viewer/validate-chunks.mjs` can check the rrweb structure and
 * `tools/replay-viewer/index.html` can play it. The test itself asserts the facts the viewer relies on.
 */
@Suppress("UNCHECKED_CAST")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayChunkExportTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val looper: Looper = Looper.getMainLooper()
    private val clock = FakeClock(1_762_300_000_000L)
    private val dispatcher = ManualDispatcher()
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
    private val sinkBatches = CopyOnWriteArrayList<NewReplayBatch>()
    private lateinit var core: ARTrackerCore
    private lateinit var activity: Activity
    private lateinit var column: LinearLayout

    private class PlainActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            super.onCreate(savedInstanceState)
        }
    }

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
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
    }

    private fun idle(ms: Long) = shadowOf(looper).idleFor(Duration.ofMillis(ms))

    /** Advances the SDK clock and the main looper together, then lets the processing side catch up. */
    private fun step(ms: Long) {
        clock.advance(ms)
        idle(ms)
        dispatcher.runAll()
    }

    private fun relayout() {
        val decor = activity.window.decorView as ViewGroup
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, WIDTH, HEIGHT)
    }

    private fun capture() {
        relayout()
        core.replayController!!.captureFrame()
        dispatcher.runAll()
    }

    private fun tap(x: Float, y: Float) {
        val down = MotionEvent.obtain(0, clock.nowMs(), MotionEvent.ACTION_DOWN, x, y, 0)
        activity.window.callback.dispatchTouchEvent(down)
        step(80)
        val up = MotionEvent.obtain(0, clock.nowMs(), MotionEvent.ACTION_UP, x, y, 0)
        activity.window.callback.dispatchTouchEvent(up)
        dispatcher.runAll()
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(WIDTH - 64, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(32, 12, 32, 0) }
    }

    private fun loginScreen(): View {
        column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(label("Welcome to ARTracker", 24f, 0xFF1D1D1F.toInt(), bold = true))
            addView(label("Sign in to continue", 14f, 0xFF6E6E73.toInt()))
            addView(
                EditText(activity).apply {
                    setText("alice@example.com")
                    tag = "email"
                    layoutParams = LinearLayout.LayoutParams(WIDTH - 64, 120).apply { setMargins(32, 24, 32, 0) }
                },
            )
            addView(
                EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText(PASSWORD)
                    tag = "password"
                    layoutParams = LinearLayout.LayoutParams(WIDTH - 64, 120).apply { setMargins(32, 12, 32, 0) }
                },
            )
            addView(
                Button(activity).apply {
                    text = "Sign in"
                    tag = "signin"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF1565C0.toInt())
                    layoutParams = LinearLayout.LayoutParams(WIDTH - 64, 140).apply { setMargins(32, 24, 32, 0) }
                },
            )
        }
        return column
    }

    private fun feedScreen(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFFF5F5F7.toInt())
        addView(label("Feed", 24f, 0xFF1D1D1F.toInt(), bold = true))
        listOf("Reservoir levels rising", "New release: v2.4", "Weekly digest", "You have 3 unread alerts").forEach {
            addView(label(it, 16f, 0xFF1D1D1F.toInt()).apply { setBackgroundColor(Color.WHITE) })
        }
    }

    @Test
    fun exportsARealisticReplay_forTheViewerAndTheNodeValidator() {
        val cfg = ARTrackerConfig(
            endpoint = "http://127.0.0.1:9",
            apiKey = "art_live_" + "a".repeat(43),
            environment = "test",
            appVersion = "1.0",
            replay = ReplayConfig(enabled = true, sampleRate = 1.0),
            beforeSend = BeforeSend { it.also(seen::add) },
        )
        core = ARTrackerCore(app, cfg, clock, replayFactory = { c, handler ->
            ReplayController.create(c, ViewWalker(MaskingPolicy(c.config.privacy)), handler, { _, _ -> true }, dispatcher)
        }, replaySink = ReplaySink { sinkBatches += it })
        ARTrackerCore.install(core)
        core.start()
        ProcessLifecycleTestUtil.start()

        activity = Robolectric.buildActivity(PlainActivity::class.java).setup().get()
        core.screens.onScreen("LoginActivity", "activity")
        activity.setContentView(loginScreen())
        step(1_000)
        capture() // Meta + FullSnapshot of the Login screen

        step(1_500)
        tap(100f, 450f) // on the Sign in button area
        step(900)

        // Mutation: a text change, a style change and an added view.
        (column.getChildAt(1) as TextView).text = "Check your credentials"
        column.getChildAt(4).setBackgroundColor(0xFF0D47A1.toInt())
        column.addView(label("Forgot your password?", 14f, 0xFF1565C0.toInt()))
        step(500)
        capture()

        step(1_200)
        // Mutation: a removed view.
        column.removeViewAt(column.childCount - 1)
        step(300)
        capture()

        step(1_500)
        // Screen change: the Feed replaces the Login (new snapshot, new href).
        core.screens.onScreen("FeedActivity", "activity")
        activity.setContentView(feedScreen())
        step(300)
        capture()
        step(1_000)
        tap(160f, 120f)
        step(1_000)

        core.replayController!!.flushChunks()
        dispatcher.runAll()
        runBlocking { withTimeout(15_000) { core.awaitIdle() } }

        assertTrue("replay must not travel through the ingest pipeline", seen.none { it.name == "replay_chunk" })
        val batches = sinkBatches.toList()
        assertTrue("expected a batch, got ${batches.size}", batches.isNotEmpty())
        val events = batches.flatMap { batch ->
            val text = GZIPInputStream(ByteArrayInputStream(batch.gzipBody)).use { String(it.readBytes(), Charsets.UTF_8) }
            val array = JSONArray(text)
            (0 until array.length()).map { plain(array.get(it)) as Map<String, Any?> }
        }

        // Meta then FullSnapshot first; two snapshots (Login, Feed); mutations; touches.
        assertEquals(listOf(4, 2), events.take(2).map { it["type"] })
        assertTrue(events.count { it["type"] == 2 } >= 2)
        assertTrue("no mutation recorded", events.any { it["type"] == 3 && (it["data"] as Map<*, *>)["source"] == 0 })
        assertTrue("no touch recorded", events.any { it["type"] == 3 && (it["data"] as Map<*, *>)["source"] == 2 })
        val json = events.joinToString(prefix = "[", postfix = "]") { Json.encode(it, Json.REPLAY_MAX_DEPTH) }
        assertTrue(json.contains("Welcome to ARTracker"))
        assertTrue(json.contains("Check your credentials"))
        assertTrue(json.contains("Forgot your password?"))
        assertTrue(json.contains("LoginActivity") && json.contains("FeedActivity"))
        // Privacy: neither the password nor the e-mail typed into inputs leaves the walker.
        assertFalse("password leaked into the recording", json.contains(PASSWORD))
        assertFalse("input text leaked into the recording", json.contains("alice@example.com"))
        assertTrue("masked input should be written as asterisks", Regex("\"\\*{14}\"").containsMatchIn(json))

        // The playground export and tools/replay-viewer still read the old chunk-attributes shape: one object per batch.
        val attributes = batches.mapIndexed { index, batch ->
            val own = events.filter { e -> (e["timestamp"] as? Number)?.toLong() in batch.firstTsMs..batch.lastTsMs }
            linkedMapOf(
                "seq" to index,
                "start" to batch.firstTsMs,
                "end" to batch.lastTsMs,
                "full_snapshot" to own.any { it["type"] == 2 },
                "platform" to "android",
                "events" to own,
            )
        }
        val dir = File("build/replay-export").apply { mkdirs() }
        File(dir, "replay-chunks.json").writeText(Json.encode(attributes, Json.REPLAY_MAX_DEPTH))
    }

    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    private companion object {
        const val WIDTH = 360
        const val HEIGHT = 640
        const val PASSWORD = "hunter2-secret"
    }
}
