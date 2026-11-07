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

    fun start(scenario: Scenario) {
        stop()
        active = scenario.id
        state = RunState.Idle
        job = scope.launch { runner.run(scenario, contextFor(scenario, ::awaitUser)) { state = it } }
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
