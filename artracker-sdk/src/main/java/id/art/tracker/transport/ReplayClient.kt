package id.art.tracker.transport

import id.art.tracker.ARTrackerSender
import id.art.tracker.SendRequest
import id.art.tracker.core.Iso8601
import id.art.tracker.core.Json
import id.art.tracker.core.SdkInfo
import id.art.tracker.core.SdkLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Outcome of uploading one replay batch (all three steps), already mapped from the HTTP statuses. */
internal sealed interface ReplayResult {
    /** The batch was uploaded and acknowledged. */
    object Done : ReplayResult

    /** Permanent failure for this batch: delete it. [reason] is a short code, never a URL, key or payload. */
    data class Drop(val reason: String) : ReplayResult

    /** 401 / 403 on `chunk-url` or `chunk-ack`: bad or revoked API key. */
    object Unauthorized : ReplayResult

    /**
     * `403 {"error":"screenshot_disabled"}` on the `chunk-url` of a screenshot chunk: the project owner switched
     * screenshot replay off. Not an auth failure: the chunk is dropped and the caller stops capturing screenshots.
     */
    object ScreenshotDisabled : ReplayResult

    /** Retry the whole upload later. [delayMs] is the server's `Retry-After` (0 = at once), null means use backoff. */
    data class Retry(val delayMs: Long?) : ReplayResult

    /** Transport-level failure (offline, DNS, timeout, TLS...). */
    data class NetworkError(val cause: Throwable) : ReplayResult
}

/** Uploads one batch. A `fun interface` so the flusher can be tested with fakes. */
internal fun interface ReplaySender {
    fun upload(upload: ReplayUpload): ReplayResult
}

internal enum class ReplayStep { CHUNK_URL, UPLOAD, ACK }

/** Maps the HTTP status of one step to a [ReplayResult]; null means the step succeeded and the next one may run. */
internal object ReplayResponse {
    fun map(step: ReplayStep, status: Int, retryAfter: String?): ReplayResult? = when {
        status in 200..299 -> null
        // The PUT carries a signed URL instead of the API key: a 401/403 there means the signature expired. Start
        // over from `chunk-url` at once, and never treat it as a revoked key.
        step == ReplayStep.UPLOAD && (status == 401 || status == 403) -> ReplayResult.Retry(0)
        status == 401 || status == 403 -> ReplayResult.Unauthorized
        status == 429 || status == 503 -> ReplayResult.Retry(IngestResponse.parseRetryAfterMs(retryAfter))
        status in 400..499 -> ReplayResult.Drop("http_$status")
        // 5xx, and unexpected 1xx/3xx (redirects are never followed): transient or misconfigured, keep the batch.
        else -> ReplayResult.Retry(null)
    }
}

internal object ReplayEndpoint {
    const val CHUNK_URL_PATH = "/api/v1/replay/chunk-url"
    const val CHUNK_ACK_PATH = "/api/v1/replay/chunk-ack"

    /** `{endpoint}{path}`, or null when [endpoint] is not a valid http(s) URL (it can then never succeed). */
    fun url(endpoint: String, path: String): URL? =
        runCatching { URL(endpoint.trimEnd('/') + path) }
            .getOrNull()
            ?.takeIf { it.protocol == "http" || it.protocol == "https" }

    /**
     * The server chooses where the batch is PUT. It must be an http(s) URL and never a downgrade: an `https` endpoint
     * must not hand the payload to a cleartext `http` URL.
     */
    fun uploadUrlAcceptable(endpoint: String, uploadUrl: String): Boolean {
        val target = runCatching { URL(uploadUrl) }.getOrNull() ?: return false
        if (target.protocol != "http" && target.protocol != "https") return false
        val base = runCatching { URL(endpoint) }.getOrNull() ?: return false
        return !(base.protocol == "https" && target.protocol != "https")
    }
}

/**
 * The three-step upload of the Session Replay API using plain [HttpURLConnection]: `POST chunk-url` (get a signed
 * URL), `PUT` the gzip body there, `POST chunk-ack`. Every step is idempotent on the server, so a retry repeats all
 * three. The API key goes only to the two `POST`s, never to the signed URL. Never throws (apart from Errors): every
 * failure is mapped to a [ReplayResult], and nothing that identifies the URL, key or payload is logged.
 */
internal class ReplayClient(
    private val endpoint: String,
    private val apiKey: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
    /** The host's transport (`NetworkConfig.sender`); null uses `HttpURLConnection`. Same steps, same mapping. */
    private val sender: ARTrackerSender? = null,
) : ReplaySender {
    private val chunkUrlUrl: URL? = ReplayEndpoint.url(endpoint, ReplayEndpoint.CHUNK_URL_PATH)
    private val chunkAckUrl: URL? = ReplayEndpoint.url(endpoint, ReplayEndpoint.CHUNK_ACK_PATH)
    private val keyUsable: Boolean = IngestEndpoint.keyUsable(apiKey)

    /** A request that failed before or while talking to the server. */
    private class Failed(val result: ReplayResult) : Exception(null, null, false, false)

    private class Response(val status: Int, val retryAfter: String?, val body: String?)

    override fun upload(upload: ReplayUpload): ReplayResult {
        if (!keyUsable) {
            SdkLog.w("replay API key contains control characters; dropping batch")
            return ReplayResult.Drop("invalid_key")
        }
        val urlStep = chunkUrlUrl
        val ackStep = chunkAckUrl
        if (urlStep == null || ackStep == null) {
            SdkLog.w("replay endpoint is not a valid http(s) URL; dropping batch")
            return ReplayResult.Drop("invalid_endpoint")
        }
        return try {
            val uploadUrl = requestUploadUrl(urlStep, upload)
            put(uploadUrl, upload)
            acknowledge(ackStep, upload)
            ReplayResult.Done
        } catch (f: Failed) {
            f.result
        }
    }

    private fun requestUploadUrl(target: URL, upload: ReplayUpload): URL {
        val body = LinkedHashMap<String, Any?>(8)
        body["session_id"] = upload.sessionId
        body["chunk_seq"] = upload.seq
        body["size_bytes"] = upload.gzipBody.size
        if (upload.userId != null) body["user_id"] = upload.userId
        body["environment"] = upload.environment
        body["start_time"] = Iso8601.format(upload.startMs)
        body["format"] = upload.format

        val response = send(ReplayStep.CHUNK_URL, target, "POST", jsonBody(body), authenticated = true, readBody = true)
        // A 403 whose reason is unreadable (a sender that returns no error bodies) counts as the owner's switch too: wrongly tripping the kill switch would silence the whole SDK, while a really revoked key
        // is caught by the next ingest 401 anyway.
        if (response.status == 403 && (isScreenshotDisabled(response.body) || response.body.isNullOrBlank())) {
            SdkLog.d("replay chunk-url answered 403 screenshot_disabled")
            throw Failed(ReplayResult.ScreenshotDisabled)
        }
        failIfNotSuccessful(ReplayStep.CHUNK_URL, response)
        val uploadUrl = parseUploadUrl(response.body)
        if (uploadUrl == null) {
            SdkLog.w("replay chunk-url response had no usable upload_url")
            throw Failed(ReplayResult.Retry(null))
        }
        if (!ReplayEndpoint.uploadUrlAcceptable(endpoint, uploadUrl)) {
            SdkLog.w("replay upload_url refused (not http(s), or a downgrade from https)")
            throw Failed(ReplayResult.Retry(null))
        }
        return URL(uploadUrl)
    }

    private fun put(target: URL, upload: ReplayUpload) {
        val response = send(
            ReplayStep.UPLOAD, target, "PUT", upload.gzipBody,
            authenticated = false, readBody = false, contentType = "application/gzip",
        )
        failIfNotSuccessful(ReplayStep.UPLOAD, response)
    }

    private fun acknowledge(target: URL, upload: ReplayUpload) {
        val body = LinkedHashMap<String, Any?>(8)
        body["session_id"] = upload.sessionId
        body["chunk_seq"] = upload.seq
        body["size_bytes"] = upload.gzipBody.size
        body["duration_ms"] = upload.durationMs
        body["event_count"] = upload.eventCount
        body["has_error"] = upload.hasError
        body["has_rage_click"] = false
        body["has_dead_click"] = false

        val response = send(ReplayStep.ACK, target, "POST", jsonBody(body), authenticated = true, readBody = false)
        failIfNotSuccessful(ReplayStep.ACK, response)
    }

    private fun jsonBody(fields: Map<String, Any?>): ByteArray = Json.encode(fields).toByteArray(Charsets.UTF_8)

    private fun failIfNotSuccessful(step: ReplayStep, response: Response) {
        val result = ReplayResponse.map(step, response.status, response.retryAfter) ?: return
        SdkLog.d("replay ${step.name.lowercase()} step answered ${response.status}")
        throw Failed(result)
    }

    private fun isScreenshotDisabled(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return try {
            JSONObject(body).opt("error") == "screenshot_disabled"
        } catch (e: Exception) {
            false
        }
    }

    private fun parseUploadUrl(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            (JSONObject(body).opt("upload_url") as? String)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun send(
        step: ReplayStep,
        target: URL,
        method: String,
        payload: ByteArray,
        authenticated: Boolean,
        readBody: Boolean,
        contentType: String = "application/json",
    ): Response {
        sender?.let { return sendThrough(it, step, target, method, payload, authenticated, contentType) }
        var conn: HttpURLConnection? = null
        try {
            conn = target.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("User-Agent", "artracker-android/${SdkInfo.VERSION}")
            if (authenticated) conn.setRequestProperty("X-Artracker-Key", apiKey)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            val status = conn.responseCode
            val retryAfter = conn.getHeaderField("Retry-After")
            val body = when {
                !readBody -> null
                status in 200..299 -> readLimited(conn.inputStream)
                // The reason of a 403 (`screenshot_disabled`) travels in the error body.
                status == 403 -> conn.errorStream?.let(::readLimited)
                else -> null
            }
            return Response(status, retryAfter, body)
        } catch (e: IllegalArgumentException) {
            // Can never succeed (e.g. a header the JDK refuses). Only the class is logged: the message may embed the key.
            SdkLog.w("replay ${step.name.lowercase()} request could not be built (${e.javaClass.simpleName})")
            throw Failed(ReplayResult.Drop("bad_request"))
        } catch (e: IOException) {
            throw Failed(ReplayResult.NetworkError(e))
        } catch (e: RuntimeException) {
            // Unexpected: not a known-permanent condition, so keep the batch and retry with backoff.
            throw Failed(ReplayResult.NetworkError(e))
        } finally {
            conn?.let { drainAndClose(it) }
        }
    }

    /** The same request, made by the host's [ARTrackerSender]. Its failures map like the built-in transport's. */
    private fun sendThrough(
        sender: ARTrackerSender,
        step: ReplayStep,
        target: URL,
        method: String,
        payload: ByteArray,
        authenticated: Boolean,
        contentType: String,
    ): Response {
        val headers = LinkedHashMap<String, String>(4)
        if (authenticated) headers["X-Artracker-Key"] = apiKey
        headers["Content-Type"] = contentType
        headers["User-Agent"] = "artracker-android/${SdkInfo.VERSION}"
        try {
            val response = sender.send(SendRequest(target.toString(), headers, payload, method))
            return Response(response.statusCode, response.retryAfter, response.body)
        } catch (e: IOException) {
            throw Failed(ReplayResult.NetworkError(e))
        } catch (e: Exception) {
            // The sender is host code: not a known-permanent condition, so keep the batch and retry with backoff.
            SdkLog.w("custom sender failed on replay ${step.name.lowercase()} (${e.javaClass.simpleName})")
            throw Failed(ReplayResult.NetworkError(e))
        }
    }

    private fun readLimited(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < MAX_BODY_BYTES) {
            val n = stream.read(buf, 0, minOf(buf.size, MAX_BODY_BYTES - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
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
        const val MAX_BODY_BYTES = 16 * 1024
        const val MAX_DRAIN_BYTES = 64 * 1024
    }
}
