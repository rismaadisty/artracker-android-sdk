package id.art.tracker.playground.scenario

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class ScenarioRunner(private val pause: suspend (Long) -> Unit = { delay(it) }) {
    /**
     * Runs the steps in order. `preflight()` is called once before the first step and its warnings end up in `Done`. A step
     * that throws ends the run with `Failed`; cancellation is reported as `Cancelled` and rethrown.
     */
    suspend fun run(scenario: Scenario, ctx: ScenarioContext, onState: (RunState) -> Unit) {
        val warnings = runCatching { ctx.preflight() }.getOrDefault(emptyList())
        try {
            scenario.steps.forEachIndexed { index, step ->
                onState(RunState.Running(index, scenario.steps.size, step.label))
                try {
                    step.run(ctx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    onState(RunState.Failed(index, e.message ?: e.javaClass.simpleName))
                    return
                }
                pause(step.pauseAfterMs)
            }
            onState(RunState.Done(warnings))
        } catch (e: CancellationException) {
            onState(RunState.Cancelled)
            throw e
        }
    }
}
