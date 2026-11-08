package id.art.tracker.transport

import id.art.tracker.core.SdkInfo
import java.net.URL

/** What a delivery to the ingest endpoint looks like, shared by the built-in transport and custom senders. */
internal object IngestEndpoint {
    const val INGEST_PATH = "/api/v1/ingest"

    /** `{endpoint}/api/v1/ingest`, or null when [endpoint] is not a valid http(s) URL (it can then never succeed). */
    fun url(endpoint: String): URL? =
        runCatching { URL(endpoint.trimEnd('/') + INGEST_PATH) }
            .getOrNull()
            ?.takeIf { it.protocol == "http" || it.protocol == "https" }

    /** A key with CR/LF or any control character must never reach an HTTP stack (header injection). */
    fun keyUsable(apiKey: String): Boolean = apiKey.none { it.code < 0x20 || it.code == 0x7F }

    fun headers(apiKey: String): Map<String, String> = linkedMapOf(
        "X-Artracker-Key" to apiKey,
        "Content-Type" to "application/json",
        "Content-Encoding" to "gzip",
        "User-Agent" to "artracker-android/${SdkInfo.VERSION}",
    )
}

/** Maps what the server answered to a [SendResult]; the same rules for every transport. */
internal object IngestResponse {
    private const val MAX_RETRY_AFTER_MS = 3_600_000L

    fun map(status: Int, retryAfter: String?): SendResult = when {
        status in 200..299 -> SendResult.Accepted
        status == 401 || status == 403 -> SendResult.Unauthorized
        status == 413 -> SendResult.TooLarge
        status == 429 || status == 503 -> SendResult.RetryAfter(parseRetryAfterMs(retryAfter))
        status in 400..499 -> SendResult.Drop(status)
        // 5xx, and unexpected 1xx/3xx (redirects are never followed): transient or misconfigured, keep the batch.
        else -> SendResult.RetryAfter(null)
    }

    /** Delta-seconds only; HTTP-dates, garbage and negatives yield null; values are capped at one hour. */
    fun parseRetryAfterMs(raw: String?): Long? {
        val s = raw?.trim()
        if (s.isNullOrEmpty() || !s.all { it in '0'..'9' }) return null
        // All digits but unparseable as Long means an absurdly large number: cap it rather than ignore it.
        val seconds = s.toLongOrNull() ?: return MAX_RETRY_AFTER_MS
        return minOf(seconds, MAX_RETRY_AFTER_MS / 1000) * 1000
    }
}
