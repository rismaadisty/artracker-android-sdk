package id.art.tracker

import java.io.IOException

/**
 * Replaces the transport that delivers the SDK's requests: the ingest batches and, when session replay is on, the
 * replay uploads. Set it through [NetworkConfig.sender].
 *
 * By default the SDK uses `HttpURLConnection` (no extra dependency). Provide a sender to route the requests
 * through your own HTTP stack: to inspect them with Chucker, to pin certificates, or to go through a corporate proxy.
 * `ARTrackerOkHttpSender` is a ready-made implementation on top of an `OkHttpClient`.
 *
 * Requests the SDK makes through it:
 * - ingest: `POST {endpoint}/api/v1/ingest`, gzip JSON body;
 * - replay (only with session replay): `POST {endpoint}/api/v1/replay/chunk-url` (JSON; the response body carries the
 *   upload URL), then `PUT` of a gzip body to that signed URL, then `POST {endpoint}/api/v1/replay/chunk-ack` (JSON).
 *   The API key header is on the two `POST`s only, never on the `PUT`.
 *
 * Contract:
 * - Honour [SendRequest.method] (`POST` or `PUT`), the headers and the body exactly. A sender that always POSTs
 *   would break the replay upload.
 * - [send] is called on a background thread and may block, but should use timeouts (the defaults of the built-in
 *   transport are 10 s to connect and 15 s to read, 30 s to read for the replay upload); a hung sender stalls delivery.
 * - Return the HTTP status of the response, `Retry-After`, and the response body (see [SendResponse.body]; the replay
 *   `chunk-url` step cannot work without it, and a `403` on a screenshot `chunk-url` carries the reason in its body: return
 *   the body of error responses too, as far as you can). The SDK maps the status: 2xx accepted; 401 and 403 disable the SDK for
 *   an hour (on the replay `PUT` they mean an expired signature and only repeat the upload); 413 splits an ingest
 *   batch; 429 and 503 retry after `Retry-After`; other 4xx drop the batch; anything else retries.
 * - Throw [IOException] for a network failure (offline, DNS, timeout, TLS); the SDK retries with backoff. Any other
 *   exception is treated the same way.
 * - Do not follow redirects and do not retry inside the sender: the SDK owns retry and backoff.
 * - Used only while `ARTracker.init` ran in the current process. A background flush in a process where `init` did not
 *   run falls back to the built-in transport (ingest and replay), because a sender cannot be persisted.
 */
@ExperimentalARTrackerApi
public fun interface ARTrackerSender {
    @Throws(IOException::class)
    public fun send(request: SendRequest): SendResponse
}

/**
 * One delivery to make: send [body] to [url] with [method] and [headers]. [body] is already in its final form (gzip for
 * ingest and for the replay upload, plain JSON for the other replay steps); do not modify it and do not re-compress it.
 * [method] is `POST` for ingest and the replay `POST`s, `PUT` for the replay upload.
 */
@ExperimentalARTrackerApi
public class SendRequest @JvmOverloads constructor(
    public val url: String,
    public val headers: Map<String, String>,
    public val body: ByteArray,
    public val method: String = "POST",
)

/**
 * The outcome of a delivery: the HTTP [statusCode], the raw `Retry-After` header if the server sent one, and the start
 * of the response [body] (up to 16 KiB, UTF-8; null when empty). Only the replay `chunk-url` step reads the body (it
 * holds the signed upload URL); ingest ignores it.
 */
@ExperimentalARTrackerApi
public class SendResponse @JvmOverloads constructor(
    public val statusCode: Int,
    public val retryAfter: String? = null,
    public val body: String? = null,
)
