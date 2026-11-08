package id.art.tracker.telemetry

/**
 * Client-side flood guard for handled errors: at most [maxPerFingerprint] reports of the same fingerprint per
 * session. Only the current session is remembered (a new session id resets the counters), and the number of
 * distinct fingerprints tracked is bounded, so memory stays small however many different errors occur.
 */
internal class ErrorDeduper(private val maxPerFingerprint: Int = 10) {
    private var sessionId: String? = null
    private val counts = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > MAX_FINGERPRINTS
    }

    @Synchronized
    fun shouldReport(sessionId: String, fingerprint: String): Boolean {
        if (this.sessionId != sessionId) {
            this.sessionId = sessionId
            counts.clear()
        }
        val n = (counts[fingerprint] ?: 0) + 1
        counts[fingerprint] = n
        return n <= maxPerFingerprint
    }

    private companion object {
        const val MAX_FINGERPRINTS = 256
    }
}
