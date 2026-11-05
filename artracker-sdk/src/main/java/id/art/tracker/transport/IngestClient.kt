package id.art.tracker.transport

import id.art.tracker.core.SdkInfo
import id.art.tracker.core.SdkLog
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * POSTs gzip envelopes to `{endpoint}/api/v1/ingest` using plain [HttpURLConnection] (no OkHttp dependency).
 * Never throws: every failure is mapped to a [SendResult].
 */
internal class IngestClient(
    endpoint: String,
    private val apiKey: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : Sender {
    // A malformed endpoint can never succeed, so it is resolved once and reported as Drop(0) on every send.
    private val url: URL? = runCatching { URL(endpoint.trimEnd('/') + INGEST_PATH) }
        .getOrNull()
        ?.takeIf { it.protocol == "http" || it.protocol == "https" }

    override fun send(gzipBody: ByteArray): SendResult {
        val target = url ?: run {
            SdkLog.w("ingest endpoint is not a valid http(s) URL; dropping batch")
            return SendResult.Drop(0)
        }
        var conn: HttpURLConnection? = null
        try {
            conn = target.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("X-Artracker-Key", apiKey)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Content-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "artracker-android/${SdkInfo.VERSION}")
            conn.setFixedLengthStreamingMode(gzipBody.size)
            conn.outputStream.use { it.write(gzipBody) }

            val status = conn.responseCode
            val retryAfter = conn.getHeaderField("Retry-After")
            return map(status, retryAfter)
        } catch (e: IllegalArgumentException) {
            // e.g. an API key containing CR/LF is rejected by setRequestProperty: can never succeed.
            SdkLog.w("ingest request could not be built", e)
            return SendResult.Drop(0)
        } catch (e: IOException) {
            return SendResult.NetworkError(e)
        } catch (e: RuntimeException) {
            // Unexpected: not a known-permanent condition, so keep the batch and retry with backoff.
            return SendResult.NetworkError(e)
        } finally {
            conn?.let { drainAndClose(it) }
        }
    }

    private fun map(status: Int, retryAfter: String?): SendResult = when {
        status in 200..299 -> SendResult.Accepted
        status == 401 || status == 403 -> SendResult.Unauthorized
        status == 413 -> SendResult.TooLarge
        status == 429 || status == 503 -> SendResult.RetryAfter(parseRetryAfterMs(retryAfter))
        status in 400..499 -> SendResult.Drop(status)
        // 5xx, and unexpected 1xx/3xx (redirects are never followed): transient or misconfigured, keep the batch.
        else -> SendResult.RetryAfter(null)
    }

    /** Delta-seconds only; HTTP-dates, garbage and negatives yield null; values are capped at one hour. */
    private fun parseRetryAfterMs(raw: String?): Long? {
        val s = raw?.trim()
        if (s.isNullOrEmpty() || !s.all { it in '0'..'9' }) return null
        // All digits but unparseable as Long means an absurdly large number: cap it rather than ignore it.
        val seconds = s.toLongOrNull() ?: return MAX_RETRY_AFTER_MS
        return minOf(seconds, MAX_RETRY_AFTER_MS / 1000) * 1000
    }

    private fun drainAndClose(conn: HttpURLConnection) {
        // Reading to EOF and closing lets the JDK return the socket to its keep-alive pool.
        runCatching { drain(conn.inputStream) }
        runCatching { drain(conn.errorStream) }
    }

    private fun drain(stream: InputStream?) {
        stream ?: return
        try {
            val buf = ByteArray(1024)
            var total = 0
            while (total < MAX_DRAIN_BYTES) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    private companion object {
        const val INGEST_PATH = "/api/v1/ingest"
        const val MAX_RETRY_AFTER_MS = 3_600_000L
        const val MAX_DRAIN_BYTES = 64 * 1024
    }
}
