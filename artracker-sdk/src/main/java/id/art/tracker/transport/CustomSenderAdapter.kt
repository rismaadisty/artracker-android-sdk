package id.art.tracker.transport

import id.art.tracker.ARTrackerSender
import id.art.tracker.SendRequest
import id.art.tracker.core.SdkLog
import java.io.IOException

/**
 * Runs the batches through a host-provided [ARTrackerSender] instead of `HttpURLConnection`.
 * Same rules as [IngestClient]: never throws (apart from Errors such as `OutOfMemoryError`), and every failure is
 * mapped to a [SendResult]. The status handling is shared with [IngestResponse].
 */
internal class CustomSenderAdapter(
    private val delegate: ARTrackerSender,
    endpoint: String,
    private val apiKey: String,
) : Sender {
    private val url: String? = IngestEndpoint.url(endpoint)?.toString()
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
        return try {
            val response = delegate.send(SendRequest(target, IngestEndpoint.headers(apiKey), gzipBody))
            IngestResponse.map(response.statusCode, response.retryAfter)
        } catch (e: IOException) {
            SendResult.NetworkError(e)
        } catch (e: Exception) {
            // The sender is host code: not a known-permanent condition, so keep the batch and retry with backoff.
            SdkLog.w("custom sender failed (${e.javaClass.simpleName})")
            SendResult.NetworkError(e)
        }
    }
}
