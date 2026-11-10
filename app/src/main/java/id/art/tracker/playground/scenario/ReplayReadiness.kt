package id.art.tracker.playground.scenario

object ReplayReadiness {
    /**
     * How long to wait so that at least one full frame of the current session has been captured: two frame periods plus
     * half a second. A touch only rides on a chunk that already holds a frame of the session, so scenarios wait
     * first. `fps` is clamped to 0.5..10.0 like the SDK does (non-finite means 1.0).
     */
    fun warmupMs(fps: Double): Long {
        val effective = if (fps.isFinite()) fps.coerceIn(0.5, 10.0) else 1.0
        return (2 * (1_000.0 / effective)).toLong() + 500
    }
}
