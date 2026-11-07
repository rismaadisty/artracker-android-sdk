package id.art.tracker.playground.scenario

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the coroutine of the running scenario and the "Lanjut" gate. The [scope] decides where steps run (the host passes the
 * main-thread scope of the screen, so leaving the screen cancels the run).
 */
class ScenarioHost(
    private val scope: CoroutineScope,
    private val contextFor: (Scenario, suspend (String) -> Unit) -> ScenarioContext,
    private val runner: ScenarioRunner = ScenarioRunner(),
) {
    var state: RunState by mutableStateOf(RunState.Idle)
        private set
    var awaiting: String? by mutableStateOf(null)
        private set
    var active: String? by mutableStateOf(null)
        private set

    private var job: Job? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var runId = 0
    private var reported = false

    fun start(scenario: Scenario) {
        stop()
        // States from a superseded run (its late Cancelled) must not overwrite this run's state.
        val id = ++runId
        reported = false
        active = scenario.id
        state = RunState.Idle
        job = scope.launch { runner.run(scenario, contextFor(scenario, ::awaitUser)) { if (id == runId) state = it } }
    }

    /** The final state (Done or Failed) once per run, then null: the screen shows it as a snackbar and goes back. */
    fun takeOutcome(): RunState? {
        val s = state
        if (reported || (s !is RunState.Done && s !is RunState.Failed)) return null
        reported = true
        return s
    }

    fun stop() {
        job?.cancel()
        job = null
        gate = null
        awaiting = null
    }

    fun continueFromUser() {
        gate?.complete(Unit)
    }

    private suspend fun awaitUser(prompt: String) {
        val deferred = CompletableDeferred<Unit>()
        gate = deferred
        awaiting = prompt
        try {
            deferred.await()
        } finally {
            awaiting = null
            gate = null
        }
    }
}
