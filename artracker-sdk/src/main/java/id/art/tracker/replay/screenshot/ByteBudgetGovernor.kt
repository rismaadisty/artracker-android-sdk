package id.art.tracker.replay.screenshot

import id.art.tracker.ReplayQuality
import id.art.tracker.lower
import java.util.ArrayDeque

/**
 * Keeps screenshot replay inside its data budget (about 5 MB per minute): a rolling 60 s window of encoded bytes.
 *
 * - Over [budgetBytes]: the effective fps is halved ([fpsCap], never below 0.5), at most once per [ADJUST_INTERVAL_MS].
 * - Still over budget after the fps reached its floor, twice in a row: the quality steps down one preset
 *   ([quality]); it never goes back up during the session.
 * - Back under 80 % of the budget: the fps cap is lifted (the gap between 80 % and 100 % is the hysteresis).
 *
 * Not thread-safe: one caller (the replay processing context).
 */
internal class ByteBudgetGovernor(
    private val baseFps: Double,
    startQuality: ReplayQuality,
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val windowMs: Long = WINDOW_MS,
) {
    private class Sample(val atMs: Long, val bytes: Long)

    private val window = ArrayDeque<Sample>()
    private var windowBytes = 0L
    private var lastAdjustMs = Long.MIN_VALUE
    private var floorOverruns = 0

    /** Upper bound for the capture rate, or null when the budget holds. */
    var fpsCap: Double? = null
        private set

    var quality: ReplayQuality = startQuality
        private set

    /** An encoded frame of [bytes] was produced at [nowMs]. */
    fun record(nowMs: Long, bytes: Long) {
        window.addLast(Sample(nowMs, bytes))
        windowBytes += bytes
        while (window.isNotEmpty() && nowMs - window.peekFirst()!!.atMs >= windowMs) {
            windowBytes -= window.removeFirst().bytes
        }
        when {
            windowBytes > budgetBytes && (lastAdjustMs == Long.MIN_VALUE || nowMs - lastAdjustMs >= ADJUST_INTERVAL_MS) -> {
                lastAdjustMs = nowMs
                tighten()
            }
            windowBytes * 10 <= budgetBytes * RECOVER_TENTHS -> {
                fpsCap = null
                floorOverruns = 0
            }
        }
    }

    private fun tighten() {
        val cap = fpsCap
        when {
            cap == null -> fpsCap = maxOf(MIN_FPS, baseFps / 2)
            cap > MIN_FPS -> fpsCap = maxOf(MIN_FPS, cap / 2)
            else -> {
                floorOverruns++
                if (floorOverruns >= FLOOR_OVERRUNS_BEFORE_QUALITY_STEP) {
                    quality.lower()?.let { quality = it }
                    floorOverruns = 0
                }
            }
        }
    }

    companion object {
        const val DEFAULT_BUDGET_BYTES: Long = 5L * 1024 * 1024
        const val WINDOW_MS: Long = 60_000
        const val ADJUST_INTERVAL_MS: Long = 10_000
        private const val MIN_FPS = 0.5
        private const val RECOVER_TENTHS = 8L
        private const val FLOOR_OVERRUNS_BEFORE_QUALITY_STEP = 2
    }
}
