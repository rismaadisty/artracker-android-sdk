package id.art.tracker.telemetry.okhttp

import id.art.tracker.LogLevel

/**
 * One finished HTTP exchange, as observed by the interceptor. SDK-owned and free of `okhttp3` types: this is the
 * seam between the (optional) OkHttp integration and the core, so nothing in the core ever references OkHttp.
 *
 * [url] has already had its sensitive query parameters and credentials removed by the producer.
 */
internal data class HttpRecord(
    val method: String,
    val url: String,
    val host: String,
    /** `null` when no response was received (the call threw). */
    val status: Int?,
    val durationMs: Long,
    /** `null` when unknown (no body or a chunked/streamed one). */
    val requestBytes: Long?,
    val responseBytes: Long?,
    val protocol: String?,
    /** Exception class name and message when the call threw, otherwise `null`. */
    val error: String?,
) {
    /** No response or 5xx is an error, 4xx a warning, everything else is informational. */
    fun level(): LogLevel = when {
        status == null || status >= 500 -> LogLevel.ERROR
        status >= 400 -> LogLevel.WARN
        else -> LogLevel.INFO
    }
}

/** Receives every finished exchange. Called on OkHttp's threads: must be fast, thread-safe and never throw. */
internal fun interface HttpReporter {
    fun report(record: HttpRecord)
}
