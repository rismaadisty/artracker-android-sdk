package id.art.tracker.transport

/** Outcome of one ingest request, already mapped from the HTTP status (ARCHITECTURE.md section 2). */
internal sealed interface SendResult {
    /** 2xx: the batch was accepted; delete it from the queue. */
    object Accepted : SendResult

    /** Permanent failure for this batch (non-retryable 4xx, or a request that can never be built). Drop it. */
    data class Drop(val status: Int) : SendResult

    /** 401 / 403: bad or revoked API key. Trigger the kill-switch. */
    object Unauthorized : SendResult

    /** 413: batch too large. Split it. */
    object TooLarge : SendResult

    /** Retryable server-side condition. [delayMs] is the server-provided `Retry-After`, or null if absent/unusable. */
    data class RetryAfter(val delayMs: Long?) : SendResult

    /** Transport-level failure (offline, DNS, timeout, TLS...). Retry with backoff. */
    data class NetworkError(val cause: Throwable) : SendResult
}

/** Sends one gzip-encoded envelope. A `fun interface` so the flusher can be tested with fakes. */
internal fun interface Sender {
    fun send(gzipBody: ByteArray): SendResult
}
