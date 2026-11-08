package id.art.tracker

import java.io.IOException

/**
 * Replaces the transport that delivers batches to the ingest endpoint. Set it through [NetworkConfig.sender].
 *
 * By default the SDK posts with `HttpURLConnection` (no extra dependency). Provide a sender to route the requests
 * through your own HTTP stack: to inspect them with Chucker, to pin certificates, or to go through a corporate proxy.
 * `ARTrackerOkHttpSender` is a ready-made implementation on top of an `OkHttpClient`.
 *
 * Contract:
 * - [send] is called on a background thread and may block, but should use timeouts (the defaults of the built-in
 *   transport are 10 s to connect and 15 s to read); a hung sender stalls delivery.
 * - Return the HTTP status of the response. The SDK maps it: 2xx accepted; 401 and 403 disable the SDK for an hour;
 *   413 splits the batch; 429 and 503 retry after `Retry-After`; other 4xx drop the batch; anything else retries.
 * - Throw [IOException] for a network failure (offline, DNS, timeout, TLS); the SDK retries with backoff. Any other
 *   exception is treated the same way.
 * - Do not follow redirects and do not retry inside the sender: the SDK owns retry and backoff.
 * - Used only while `ARTracker.init` ran in the current process. A background flush in a process where `init` did not
 *   run falls back to the built-in transport, because a sender cannot be persisted.
 */
@ExperimentalARTrackerApi
public fun interface ARTrackerSender {
    @Throws(IOException::class)
    public fun send(request: SendRequest): SendResponse
}

/**
 * One delivery to make: POST [body] to [url] with [headers]. [body] is already gzip-compressed JSON; do not modify it
 * and do not re-compress it.
 */
@ExperimentalARTrackerApi
public class SendRequest(
    public val url: String,
    public val headers: Map<String, String>,
    public val body: ByteArray,
)

/** The outcome of a delivery: the HTTP [statusCode] and the raw `Retry-After` header, if the server sent one. */
@ExperimentalARTrackerApi
public class SendResponse @JvmOverloads constructor(
    public val statusCode: Int,
    public val retryAfter: String? = null,
)
