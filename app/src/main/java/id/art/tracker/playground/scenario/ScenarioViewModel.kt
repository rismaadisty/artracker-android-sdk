package id.art.tracker.playground.scenario

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/** What the running scenario needs from the current screen; re-bound after every recomposition/rotation. */
class ScenarioBindings {
    @Volatile var navigate: (String) -> Unit = {}
    @Volatile var replayOn: () -> Boolean = { false }
    @Volatile var replayFps: () -> Double = { 1.0 }
    @Volatile var note: (String) -> Unit = {}

    fun reset() {
        navigate = {}
        replayOn = { false }
        replayFps = { 1.0 }
        note = {}
    }
}

/**
 * Owns the [ScenarioHost] so a run survives a configuration change. It never holds the Activity or the NavController: it
 * reaches them through [bindings], which the host composable sets and clears.
 */
class ScenarioViewModel : ViewModel() {
    val bindings = ScenarioBindings()

    val host = ScenarioHost(
        scope = viewModelScope,
        contextFor = { scenario, gate ->
            AppScenarioContext(
                proves = scenario.proves,
                navigateTo = { bindings.navigate(it) },
                activity = { ActivityHolder.resumed },
                replayOn = { bindings.replayOn() },
                replayFps = { bindings.replayFps() },
                userGate = gate,
                notes = { bindings.note(it) },
            )
        },
    )

    override fun onCleared() {
        host.stop()
    }
}
