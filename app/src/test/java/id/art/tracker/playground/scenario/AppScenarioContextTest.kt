package id.art.tracker.playground.scenario

import android.app.Activity
import android.app.Application
import android.graphics.PointF
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppScenarioContextTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(ComponentActivity::class.java).create().get() }
    private val registry = TapRegistry()
    private val taps = mutableListOf<PointF>()
    private val slept = mutableListOf<Long>()

    private fun ctx(
        proves: Set<Capability> = setOf(Capability.EVENTS),
        act: () -> Activity? = { activity },
        replayOn: Boolean = true,
        recorder: Boolean = true,
        fps: Double = 1.0,
    ) = AppScenarioContext(
        proves = proves, navigateTo = { }, activity = act, replayOn = { replayOn }, replayFps = { fps },
        userGate = { }, registry = registry, recorderInstalled = { recorder },
        touch = { _, p -> taps += p }, sleep = { slept += it }, tapWaitMs = 50,
    )

    @Test
    fun noActivityIsWarned() {
        assertTrue(ctx(act = { null }).preflight().any { it.contains("tidak ada Activity") })
    }

    @Test
    fun replayOffIsWarnedBecauseTouchesWillNotBeRecorded() {
        assertTrue(ctx(replayOn = false).preflight().any { it.contains("replay tidak merekam") })
    }

    @Test
    fun replayOnWithoutTheSdkRecorderIsWarned() {
        assertTrue(ctx(recorder = false).preflight().any { it.contains("perekam sentuhan") })
    }

    @Test
    fun aHealthyNonReplayScenarioHasNoWarnings() {
        assertEquals(emptyList<String>(), ctx().preflight())
    }

    @Test
    fun aReplayScenarioAlwaysCarriesTheDialogLimitation() {
        val warnings = ctx(proves = setOf(Capability.REPLAY)).preflight()
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("dialog"))
    }

    @Test
    fun tapOnAMissingTargetFailsWithItsName() {
        try {
            runBlocking { ctx().tap("nope") }
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("nope"))
        }
    }

    @Test
    fun tapOnATargetInAnotherWindowFails() {
        registry.register("dlg", TapTarget(Rect(0f, 0f, 10f, 10f), inActivityWindow = false))
        try {
            runBlocking { ctx().tap("dlg") }
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("window lain"))
        }
        assertTrue(taps.isEmpty())
    }

    @Test
    fun tapOnAnActivityTargetTouchesItsCenter() {
        registry.register("ok", TapTarget(Rect(10f, 20f, 30f, 60f), inActivityWindow = true))
        runBlocking { ctx().tap("ok") }
        assertEquals(1, taps.size)
        assertEquals(20f, taps.single().x, 0f)
        assertEquals(40f, taps.single().y, 0f)
    }

    @Test
    fun tapWithoutAnActivityFails() {
        registry.register("ok", TapTarget(Rect(0f, 0f, 10f, 10f), inActivityWindow = true))
        try {
            runBlocking { ctx(act = { null }).tap("ok") }
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Activity"))
        }
    }

    @Test
    fun typeIntoAnUnknownFieldFails() {
        try {
            runBlocking { ctx().type("missing-field", "x") }
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing-field"))
        }
    }

    @Test
    fun awaitReplayFrameSleepsTheWarmupForTheConfiguredFps() {
        runBlocking { ctx(fps = 0.5).awaitReplayFrame() }
        assertEquals(listOf(ReplayReadiness.warmupMs(0.5)), slept)
    }

    @Test
    fun awaitUserDelegatesToTheGate() {
        var asked: String? = null
        val c = AppScenarioContext(
            proves = emptySet(), navigateTo = { }, activity = { null }, replayOn = { false }, replayFps = { 1.0 },
            userGate = { asked = it }, sleep = { },
        )
        runBlocking { c.awaitUser("Aktifkan mode pesawat") }
        assertEquals("Aktifkan mode pesawat", asked)
        assertFalse(asked.isNullOrBlank())
    }
}
