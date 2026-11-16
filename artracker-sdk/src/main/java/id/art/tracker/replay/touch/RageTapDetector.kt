package id.art.tracker.replay.touch

import kotlin.math.abs

/**
 * Recognises a rage tap: [minTaps] or more taps within [windowMs], each within [radiusDp] of the FIRST tap of the burst
 * (not of the previous one, so a finger that creeps across the screen does not count). Both limits are inclusive.
 *
 * The window slides: the last [minTaps] taps are kept, and on every new tap the oldest ones that are too old or too far
 * from it are dropped, so the oldest tap still kept is the first of the burst. A stray tap before a quick triple tap
 * (tap, pause, tap-tap-tap) does not hide it, which anchoring the window on the very first tap would.
 *
 * Fed with the `DOWN` of every touch, in dp (`ScreenTouch.xDp` / `yDp`) and with the SDK clock; nothing else counts, so
 * moves and ups between taps neither break nor extend a burst. A tap earlier than the last one (the clock went back)
 * makes the history incomparable and starts over.
 *
 * Pure logic, no Android. Not thread-safe: one caller ([id.art.tracker.replay.screenshot.ScreenChunker]).
 */
internal class RageTapDetector(
    private val windowMs: Long = WINDOW_MS,
    private val radiusDp: Int = RADIUS_DP,
    private val minTaps: Int = MIN_TAPS,
) {
    private class Tap(val ts: Long, val x: Int, val y: Int)

    private val taps = ArrayDeque<Tap>(minTaps + 1)

    /** True while the last [minTaps] taps (this one included) form a burst: for the [minTaps]-th tap and any further one. */
    fun onDown(ts: Long, xDp: Int, yDp: Int): Boolean {
        if (taps.isNotEmpty() && ts < taps.last().ts) taps.clear()
        while (taps.isNotEmpty() && (ts - taps.first().ts > windowMs || !isNear(taps.first(), xDp, yDp))) taps.removeFirst()
        taps.addLast(Tap(ts, xDp, yDp))
        while (taps.size > minTaps) taps.removeFirst()
        return taps.size >= minTaps
    }

    /** Forgets the burst in progress (a new session, or the recording was dropped). */
    fun reset() {
        taps.clear()
    }

    private fun isNear(tap: Tap, xDp: Int, yDp: Int): Boolean {
        val dx = xDp.toLong() - tap.x
        val dy = yDp.toLong() - tap.y
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
