package id.art.tracker.transport

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
    private val url: URL? = IngestEndpoint.url(endpoint)

    // Defence in depth behind ARTrackerConfig.validate(): a key with CR/LF or any control character must never reach
    // the HTTP stack (the JDK opens the connection before it rejects such a header).
    private val keyUsable: Boolean = IngestEndpoint.keyUsable(apiKey)

    override fun send(gzipBody: ByteArray): SendResult {
        if (!keyUsable) {
            SdkLog.w("ingest API key contains control characters; dropping batch")
            return SendResult.Drop(0)
        }
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
            for ((name, value) in IngestEndpoint.headers(apiKey)) conn.setRequestProperty(name, value)
            conn.setFixedLengthStreamingMode(gzipBody.size)
            conn.outputStream.use { it.write(gzipBody) }

            val status = conn.responseCode
            val retryAfter = conn.getHeaderField("Retry-After")
            return IngestResponse.map(status, retryAfter)
        } catch (e: IllegalArgumentException) {
            // e.g. an API key containing CR/LF is rejected by setRequestProperty: can never succeed. Only the class is
            // logged: the exception message embeds the offending header value (the key).
            SdkLog.w("ingest request could not be built (${e.javaClass.simpleName})")
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
        const val MAX_DRAIN_BYTES = 64 * 1024
    }
}
