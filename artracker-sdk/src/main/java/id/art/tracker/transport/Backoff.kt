package id.art.tracker.transport

import java.util.Random

/**
 * Exponential backoff with full jitter: `random(0..min(maxMs, baseMs * 2^attempt))`.
 * Stateless apart from the injected [random]; [reset] exists for symmetry with callers that track attempts.
 */
internal class Backoff(
    private val baseMs: Long = 2_000,
    private val maxMs: Long = 300_000,
    private val random: Random = Random(),
) {
    fun delayFor(attempt: Int): Long {
        var ceiling = baseMs.coerceIn(0L, maxMs)
        var n = attempt
        // Doubling with an early exit at maxMs keeps this overflow-free for any attempt value.
        while (n > 0 && ceiling < maxMs) {
            ceiling = if (ceiling > maxMs / 2) maxMs else ceiling * 2
            n--
        }
        if (ceiling <= 0L) return 0L
        return (random.nextDouble() * (ceiling + 1)).toLong().coerceIn(0L, ceiling)
    }

    fun reset() {
        // No internal state: the caller owns the attempt counter.
    }
}
