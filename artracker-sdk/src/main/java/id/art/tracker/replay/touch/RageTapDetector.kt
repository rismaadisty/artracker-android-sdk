package id.art.tracker.replay.touch

import kotlin.math.abs

/**
 * Recognises a rage tap: [minTaps] or more taps within [windowMs], each within [radiusDp] of the FIRST tap of the burst
 * (not of the previous one, so a finger that creeps across the screen does not count). Both limits are inclusive.
 *
 * Fed with the `DOWN` of every touch, in dp (`ScreenTouch.xDp` / `yDp`) and with the SDK clock; nothing else counts, so
 * moves and ups between taps neither break nor extend a burst. A tap that does not belong to the burst in progress
 * (too late, too far, or earlier than its first tap because the clock went back) starts a new burst of its own.
 *
 * Pure logic, no Android. Not thread-safe: one caller ([id.art.tracker.replay.screenshot.ScreenChunker]).
 */
internal class RageTapDetector(
    private val windowMs: Long = WINDOW_MS,
    private val radiusDp: Int = RADIUS_DP,
    private val minTaps: Int = MIN_TAPS,
) {
    private var startTs = 0L
    private var x0 = 0
    private var y0 = 0
    private var count = 0

    /** True for the [minTaps]-th tap of a burst and for every further tap of the same burst. */
    fun onDown(ts: Long, xDp: Int, yDp: Int): Boolean {
        if (count > 0 && ts - startTs in 0..windowMs && isNearAnchor(xDp, yDp)) {
            count++
        } else {
            startTs = ts
            x0 = xDp
            y0 = yDp
            count = 1
        }
        return count >= minTaps
    }

    /** Forgets the burst in progress (a new session, or the recording was dropped). */
    fun reset() {
        count = 0
    }

    private fun isNearAnchor(xDp: Int, yDp: Int): Boolean {
        val dx = xDp.toLong() - x0
        val dy = yDp.toLong() - y0
        // The cheap box test first: squaring an extreme coordinate difference would overflow a Long.
        if (abs(dx) > radiusDp || abs(dy) > radiusDp) return false
        return dx * dx + dy * dy <= radiusDp.toLong() * radiusDp
    }

    companion object {
        const val WINDOW_MS = 1_000L
        const val RADIUS_DP = 24
        const val MIN_TAPS = 3
    }
}
