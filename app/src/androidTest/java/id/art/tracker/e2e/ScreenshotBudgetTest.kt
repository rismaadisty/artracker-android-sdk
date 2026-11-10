package id.art.tracker.e2e

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.art.tracker.ARTracker
import id.art.tracker.MockIngest
import id.art.tracker.TestPlaygroundApplication
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Acceptance 4 of the screenshot design, measured on a real device: continuous scrolling at `STANDARD`, 2 fps.
 *
 * - data: the encoded bytes uploaded must stay within 25 MB for 5 minutes (scaled linearly for a shorter run);
 * - jank: the share of frames over 16 ms (`FrameMetrics`) with screenshot replay may exceed the same scrolling without
 *   replay by at most 2 percentage points.
 *
 * `-e durationMs 300000` runs the full 5 minutes per phase (default 60 s). The figures are sent as instrumentation status
 * (`am instrument -r`) so they can be copied into PERF.md.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotBudgetTest {
    @get:Rule
    val scenario = ActivityScenarioRule(ComponentActivity::class.java)

    private val ingest get() = TestPlaygroundApplication.ingest
    private val args get() = InstrumentationRegistry.getArguments()
    private val durationMs get() = args.getString("durationMs")?.toLongOrNull() ?: 60_000L

    @Before
    fun clean() = ingest.clear()

    private class Jank(val frames: Int, val over16ms: Int) {
        val percent: Double get() = if (frames == 0) 0.0 else over16ms * 100.0 / frames
    }

    /** Scrolls a list of realistic rows without pause for [ms] and counts the frames over 16 ms. */
    private fun scrollAndMeasure(ms: Long): Jank {
        val thread = HandlerThread("budget-frames").apply { start() }
        var frames = 0
        var over = 0
        lateinit var listener: android.view.Window.OnFrameMetricsAvailableListener
        scenario.scenario.onActivity { activity ->
            listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames++
                if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > 16_000_000L) over++
            }
            activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }
        Thread.sleep(ms)
        scenario.scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        thread.quitSafely()
        return Jank(frames, over)
    }

    private fun showList() {
        scenario.scenario.onActivity { activity ->
            activity.setContent {
                val state = rememberLazyListState()
                LaunchedEffect(Unit) {
                    while (true) { // 1000 px/s, forever; back to the top at the end of the list
                        if (!state.canScrollForward) state.scrollToItem(0)
                        state.animateScrollBy(2_000f, tween(2_000, easing = LinearEasing))
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().background(Color.White), state = state) {
                    items((1..5_000).toList()) { n ->
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(56.dp).background(Color((0xFF000000 + n * 7919 % 0xFFFFFF).toInt())))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text("Produk nomor $n")
                                Text("Rp ${n * 1_250} · stok ${n % 40}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun screenBytes(seen: List<MockIngest.Recorded>): Long =
        seen.filter { it.method == "POST" && it.path.substringBefore('?') == MockIngest.CHUNK_URL_PATH }
            .mapNotNull { runCatching { JSONObject(it.body) }.getOrNull() }
            .filter { it.optString("format") == "artracker-screen-v1" }
            .sumOf { it.optLong("size_bytes") }

    private fun frameCount(seen: List<MockIngest.Recorded>): Int =
        seen.filter { it.method == "PUT" && it.path.substringBefore('?') == MockIngest.UPLOAD_PATH }
            .mapNotNull { runCatching { JSONObject(it.body) }.getOrNull() }
            .filter { it.optString("format") == "artracker-screen-v1" }
            .sumOf { it.getJSONArray("frames").length() }

    @Test
    fun fiveMinutesOfScrolling_staysInTheDataBudget_andCostsAtMostTwoPointsOfJank() {
        ARTracker.stopReplay()
        showList()
        Thread.sleep(3_000)

        val baseline = scrollAndMeasure(durationMs)

        ingest.clear()
        ARTracker.startReplay()
        Thread.sleep(3_000) // warm-up of the recording; not measured
        val withReplay = scrollAndMeasure(durationMs)
        ARTracker.stopReplay()
        ARTracker.flush()
        // let the uploads finish: nothing new for 8 s
        var last = -1L
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - stableSince < 8_000) {
            Thread.sleep(500)
            val now = screenBytes(ingest.snapshot()) + ingest.snapshot().size
            if (now != last) { last = now; stableSince = System.currentTimeMillis() }
        }
        val seen = ingest.snapshot()
        val bytes = screenBytes(seen)
        val frames = frameCount(seen)
        val megabytes = bytes / 1_048_576.0
        val allowedMb = 25.0 * durationMs / 300_000.0
        val deltaPp = withReplay.percent - baseline.percent

        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("budget_report", "duration=${durationMs / 1000}s frames_uploaded=$frames chunk_MB=%.2f allowed_MB=%.2f ".format(megabytes, allowedMb) +
                    "jank_baseline=%.2f%% (%d/%d) jank_replay=%.2f%% (%d/%d) delta_pp=%.2f".format(
                        baseline.percent, baseline.over16ms, baseline.frames, withReplay.percent, withReplay.over16ms, withReplay.frames, deltaPp,
                    ))
            },
        )
        assertTrue("uploaded ${"%.2f".format(megabytes)} MB, allowed ${"%.2f".format(allowedMb)} MB", megabytes <= allowedMb)
        assertTrue("jank rose by ${"%.2f".format(deltaPp)} points (baseline ${"%.2f".format(baseline.percent)}%)", deltaPp <= 2.0)
    }
}
