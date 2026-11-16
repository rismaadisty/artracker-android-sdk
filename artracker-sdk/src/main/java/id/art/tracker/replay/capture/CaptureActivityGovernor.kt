package id.art.tracker.replay.capture

internal enum class CaptureState { ACTIVE, IDLE, DORMANT }

internal enum class TriggerKind { SCREEN, WINDOW, ERROR, NETWORK, KEYBOARD, MANUAL }

/**
 * Pure, clock-injected state machine of the replay capture lifecycle (Active -> Idle -> Dormant), driven by real
 * user input only. It decides the periodic rate, whether the forced keyframe is allowed and whether (and after
 * which debounce) an important-change trigger is admitted. No Android types, no clock: every call carries `nowMs`.
 * The iOS SDK implements the same contract with the same names. Main thread only.
 */
internal class CaptureActivityGovernor(
    private val activeFps: Double,
    private val idleAfterMs: Long = 30_000,
    private val dormantAfterMs: Long = 180_000,
) {
    var state: CaptureState = CaptureState.ACTIVE
        private set

    private var lastInputMs = 0L
    private var lastNetworkAdmittedMs: Long? = null

    /** Recording begins: ACTIVE, the clock of idleness starts now. */
    fun onStart(nowMs: Long) {
        state = CaptureState.ACTIVE
        lastInputMs = nowMs
        lastNetworkAdmittedMs = null
    }

    /** User input. True if the state was IDLE or DORMANT, i.e. the caller must take a wake capture. */
    fun onUserInput(nowMs: Long): Boolean {
        val wasQuiet = evaluate(nowMs) != CaptureState.ACTIVE
        state = CaptureState.ACTIVE
        lastInputMs = nowMs
        return wasQuiet
    }

    /** Advances the state by the time elapsed since the last user input. */
    fun evaluate(nowMs: Long): CaptureState {
        val quietMs = nowMs - lastInputMs
        state = when {
            quietMs >= dormantAfterMs -> CaptureState.DORMANT
            quietMs >= idleAfterMs -> CaptureState.IDLE
            else -> CaptureState.ACTIVE
        }
        return state
    }

    /** Periodic capture rate of the current state; null = no periodic tick at all. */
    fun periodicFps(): Double? = when (state) {
        CaptureState.ACTIVE -> activeFps
        CaptureState.IDLE -> IDLE_FPS
        CaptureState.DORMANT -> null
    }

    /** The 120 s forced keyframe only runs while the user is active. */
    val keyframeAllowed: Boolean get() = state == CaptureState.ACTIVE

    /**
     * Debounce in ms before capturing for [kind], or null if the trigger is dropped by the NETWORK rate limit
     * (IDLE 1 per 5 s, DORMANT 1 per 10 s, none in ACTIVE). A trigger never changes [state].
     */
    fun admitTrigger(kind: TriggerKind, nowMs: Long): Long? {
        evaluate(nowMs)
        if (kind == TriggerKind.NETWORK) {
            val windowMs = when (state) {
                CaptureState.ACTIVE -> 0L
                CaptureState.IDLE -> NETWORK_IDLE_WINDOW_MS
                CaptureState.DORMANT -> NETWORK_DORMANT_WINDOW_MS
            }
            val last = lastNetworkAdmittedMs
            if (last != null && nowMs - last < windowMs) return null
            lastNetworkAdmittedMs = nowMs
        }
        return when (kind) {
            TriggerKind.NETWORK -> NETWORK_DEBOUNCE_MS
            TriggerKind.MANUAL -> 0L
            else -> DEBOUNCE_MS
        }
    }

    /** When [evaluate] will next change the state if no input arrives; null once DORMANT. */
    fun nextTransitionAtMs(): Long? = when (state) {
        CaptureState.ACTIVE -> lastInputMs + idleAfterMs
        CaptureState.IDLE -> lastInputMs + dormantAfterMs
        CaptureState.DORMANT -> null
    }

    companion object {
        const val IDLE_FPS = 0.2
        private const val DEBOUNCE_MS = 300L
        private const val NETWORK_DEBOUNCE_MS = 500L
        private const val NETWORK_IDLE_WINDOW_MS = 5_000L
        private const val NETWORK_DORMANT_WINDOW_MS = 10_000L
    }
}
