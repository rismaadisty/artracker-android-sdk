package id.art.tracker.playground.labs

object SessionAdvice {
    /** How long to stay in the background so the SDK rotates the session on return: the timeout plus two seconds. */
    fun waitSeconds(sessionTimeoutMs: Long): Long = sessionTimeoutMs / 1_000 + 2
}
