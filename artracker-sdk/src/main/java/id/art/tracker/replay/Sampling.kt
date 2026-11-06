package id.art.tracker.replay

/**
 * Per-session replay sampling, an exact port of the web SDK (`artracker-web-sdk/src/core/sampling.ts`) so a
 * session id gets the same decision on every platform: FNV-1a 32-bit over the UTF-16 code units of the id
 * (JavaScript `charCodeAt`), as an unsigned value divided by 2^32, compared with the rate.
 */
internal object Sampling {
    private const val OFFSET_BASIS = 0x811c9dc5.toInt()
    private const val PRIME = 0x01000193
    private const val TWO_POW_32 = 4_294_967_296.0

    /** `rate >= 1` always, `rate <= 0` (or NaN) never, otherwise `hash32(sessionId) / 2^32 < rate`. */
    fun isSampled(sessionId: String, rate: Double): Boolean {
        if (rate >= 1.0) return true
        if (!(rate > 0.0)) return false
        return hash32(sessionId) / TWO_POW_32 < rate
    }

    /** FNV-1a 32-bit hash as an unsigned value (same as the web SDK's `hash32`). */
    fun hash32(s: String): Long {
        var h = OFFSET_BASIS
        for (c in s) {
            h = h xor c.code
            h *= PRIME
        }
        return h.toLong() and 0xFFFF_FFFFL
    }
}
