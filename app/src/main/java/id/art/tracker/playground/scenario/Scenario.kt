package id.art.tracker.playground.scenario

/** What a scenario demonstrates, so the screen can show which SDK capabilities each one proves. */
enum class Capability { EVENTS, IDENTITY, SCREENS, LOGS, ERRORS, HTTP, REPLAY, MASKING, REDACTION, OFFLINE, SESSION, QUEUE }

class Step(val label: String, val pauseAfterMs: Long = 600, val run: suspend ScenarioContext.() -> Unit)

class Scenario(
    val id: String,
    val title: String,
    val description: String,
    val proves: Set<Capability>,
    val steps: List<Step>,
)

/** What a step may do. A missing target, a missing Activity or a target in another window fails the step loudly. */
interface ScenarioContext {
    suspend fun navigate(route: String)

    /** Dispatches a real touch to the registered target; fails the step if it cannot (never a silent no-op). */
    suspend fun tap(targetId: String)

    suspend fun type(fieldId: String, text: String)

    /** Suspends until the user presses "Lanjut". */
    suspend fun awaitUser(prompt: String)

    /** Waits long enough for the replay to have a frame of the current session (a touch only rides on a chunk that already holds one). */
    suspend fun awaitReplayFrame()

    /** Warnings about conditions that make the run less meaningful (replay not recording, no recorder, dialogs). */
    fun preflight(): List<String>

    fun note(message: String)
}

sealed interface RunState {
    object Idle : RunState
    data class Running(val index: Int, val total: Int, val label: String) : RunState
    data class Done(val warnings: List<String>) : RunState
    data class Failed(val index: Int, val message: String) : RunState
    object Cancelled : RunState
}
