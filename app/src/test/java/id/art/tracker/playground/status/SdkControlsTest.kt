package id.art.tracker.playground.status

import id.art.tracker.playground.settings.PlaygroundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FakeStore(override var optedOut: Boolean = false) : ControlStore

internal class FakeSwitches : SdkSwitches {
    val calls = mutableListOf<String>()
    override fun optOut(e: Boolean) { calls += "optOut($e)" }
    override fun startReplay() { calls += "startReplay" }
    override fun stopReplay() { calls += "stopReplay" }
}

class SdkControlsTest {
    @Test
    fun optOutSurvivesRelaunch() {
        val store = FakeStore()
        SdkControls(store, PlaygroundSettings.DEFAULT, FakeSwitches()).setOptOut(true)
        val relaunched = SdkControls(store, PlaygroundSettings.DEFAULT, FakeSwitches())
        assertTrue(relaunched.optedOut)
    }

    @Test
    fun replayIntentStartsFromSettingsOnEveryStart() {
        val store = FakeStore()
        val first = SdkControls(store, PlaygroundSettings.DEFAULT.copy(replayEnabled = true), FakeSwitches())
        first.setReplay(false)
        assertFalse(first.replayOn)
        assertTrue(SdkControls(store, PlaygroundSettings.DEFAULT.copy(replayEnabled = true), FakeSwitches()).replayOn)
        assertFalse(SdkControls(store, PlaygroundSettings.DEFAULT.copy(replayEnabled = false), FakeSwitches()).replayOn)
    }

    @Test
    fun togglesCallTheSdkOnce() {
        val sdk = FakeSwitches()
        val controls = SdkControls(FakeStore(), PlaygroundSettings.DEFAULT, sdk)
        controls.setOptOut(true)
        controls.setReplay(false)
        controls.setReplay(true)
        assertEquals(listOf("optOut(true)", "stopReplay", "startReplay"), sdk.calls)
    }
}
