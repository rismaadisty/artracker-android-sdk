package id.art.tracker.appstart

/**
 * The adaptive sampling interval of the startup profiler. The mean cost of the samples of one [WINDOW] is compared with
 * [thresholdNanos]: over it, the interval doubles (10 -> 20 -> 40 ms, capped at [maxMs]) and the window starts again.
 * A window, not one sample, decides: the first stack read of a process is slow (class loading) and must not slow the
 * whole profile down. Not thread safe: used by the sampler thread only.
 */
internal class IntervalPolicy(
    initialMs: Long = 10,
    private val maxMs: Long = 40,
    private val thresholdNanos: Long = 1_000_000,
) {
    var intervalMs: Long = initialMs
        private set

    private var windowCost = 0L
    private var windowCount = 0

    /** Adds one sample's cost; at the end of a window, doubles the interval when the mean cost exceeds the threshold. */
    fun record(costNanos: Long) {
        windowCost += costNanos
        if (++windowCount < WINDOW) return
        if (windowCost / windowCount > thresholdNanos) intervalMs = minOf(maxMs, intervalMs * 2)
        windowCost = 0
        windowCount = 0
    }

    companion object {
        /** Samples per decision. */
        const val WINDOW = 5
    }
}
