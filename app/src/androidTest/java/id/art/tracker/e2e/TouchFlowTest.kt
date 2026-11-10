package id.art.tracker.e2e

import android.graphics.PointF
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.art.tracker.ARTracker
import id.art.tracker.TestPlaygroundApplication
import id.art.tracker.playground.scenario.AppScenarioContext
import id.art.tracker.playground.scenario.ReplayReadiness
import id.art.tracker.playground.scenario.SyntheticTouch
import id.art.tracker.playground.scenario.TapRegistry
import id.art.tracker.playground.scenario.tapTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Proves the synthetic tap goes through the real SDK: a click reaches app code (an event) and, only while replay records,
 * the SDK's TouchRecorder turns the same touch into down/up touches of the screen chunk at the right place.
 */
@RunWith(AndroidJUnit4::class)
class TouchFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ingest get() = TestPlaygroundApplication.ingest

    @Before
    fun clean() = ingest.clear()

    private fun showClickableTarget() {
        rule.setContent { Box(Modifier.size(160.dp).tapTarget("e2e_target").clickable { ARTracker.track("tap_target_clicked") }) }
        rule.waitForIdle()
    }

    private fun centerOfTarget(): PointF {
        val c = TapRegistry.Shared.find("e2e_target")!!.boundsInWindow.center
        return PointF(c.x, c.y)
    }

    @Test
    fun syntheticTapIsRecordedInReplayChunk() {
        ARTracker.startReplay()
        showClickableTarget()
        Thread.sleep(ReplayReadiness.warmupMs(1.0)) // a frame of this session must exist before the touch
        val center = centerOfTarget()
        val origin = SyntheticTouch.originOf(rule.activity)
        assertTrue("replay must have installed the SDK recorder", SyntheticTouch.isRecorderInstalled(rule.activity))

        runBlocking { SyntheticTouch.tap(rule.activity, center) }
        Thread.sleep(1_500)
        ARTracker.stopReplay()
        ARTracker.flush()

        val seen = ingest.awaitUntil(15_000) { it.hasItemNamed("tap_target_clicked") && it.chunks().touches().size >= 2 }
        assertTrue("the click must reach app code", seen.hasItemNamed("tap_target_clicked"))
        val touches = seen.chunks().touches()
        val start = touches.firstOrNull { it.first == "down" }
        val end = touches.firstOrNull { it.first == "up" }
        assertTrue("down touch missing: $touches", start != null)
        assertTrue("up touch missing: $touches", end != null)
        val density = rule.activity.resources.displayMetrics.density
        val expectedX = (center.x + origin.x) / density
        val expectedY = (center.y + origin.y) / density
        assertTrue("x=${start!!.second} expected ~$expectedX", abs(start.second - expectedX) <= 2)
        assertTrue("y=${start.third} expected ~$expectedY", abs(start.third - expectedY) <= 2)
    }

    @Test
    fun tapWithoutReplayStillClicksButRecordsNoTouchAndPreflightWarns() {
        ARTracker.stopReplay()
        showClickableTarget()
        Thread.sleep(800)
        ingest.clear() // drop whatever the stopped recording flushed
        val center = centerOfTarget()
        assertFalse(SyntheticTouch.isRecorderInstalled(rule.activity))

        runBlocking { SyntheticTouch.tap(rule.activity, center) }
        ARTracker.flush()

        val seen = ingest.awaitUntil(10_000) { it.hasItemNamed("tap_target_clicked") }
        assertTrue("the click must still reach app code", seen.hasItemNamed("tap_target_clicked"))
        Thread.sleep(1_500)
        assertEquals("no touch may be recorded while replay is stopped", emptyList<Any>(), ingest.snapshot().chunks().touches())

        val ctx = AppScenarioContext(
            proves = setOf(), navigateTo = { }, activity = { rule.activity }, replayOn = { false }, replayFps = { 1.0 }, userGate = { },
        )
        assertTrue(ctx.preflight().any { it.contains("replay tidak merekam") })
    }

    @Test
    fun tapOutsideTheActivityWindowFailsTheStep() {
        rule.setContent { Dialog(onDismissRequest = { }) { Box(Modifier.size(80.dp).tapTarget("dlg_target")) } }
        rule.waitForIdle()
        val target = TapRegistry.Shared.find("dlg_target")!!
        assertFalse("a Dialog lives in its own window", target.inActivityWindow)

        val ctx = AppScenarioContext(
            proves = setOf(), navigateTo = { }, activity = { rule.activity }, replayOn = { true }, replayFps = { 1.0 }, userGate = { },
        )
        try {
            runBlocking { ctx.tap("dlg_target") }
            fail("a tap on a dialog target must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("dlg_target"))
        }
    }
}
