package id.art.tracker.replay

/**
 * Rate-limits error-triggered replay uploads. An admitted call at time `t` blocks calls with `nowMs - t < minGapMs`;
 * only admitted calls count toward [maxPerSession] and reset the gap clock. A different session id resets both.
 * Not thread-safe: main thread only.
 */
internal class ErrorTriggerPolicy(
    private val maxPerSession: Int = 3,
    private val minGapMs: Long = 60_000,
) {
    private var sessionId: String? = null
    private var admittedCount = 0
    private var lastAdmittedMs: Long? = null

    /** True means promote now. */
    fun admit(sessionId: String, nowMs: Long): Boolean {
        if (sessionId != this.sessionId) {
            this.sessionId = sessionId
            admittedCount = 0
            lastAdmittedMs = null
        }
        if (admittedCount >= maxPerSession) return false
        val last = lastAdmittedMs
        if (last != null && nowMs - last < minGapMs) return false
        admittedCount++
        lastAdmittedMs = nowMs
        return true
    }
}
