package id.art.tracker.telemetry.okhttp

import id.art.tracker.ARTrackerSender
import id.art.tracker.ExperimentalARTrackerApi
import id.art.tracker.SendRequest
import id.art.tracker.SendResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Delivers the SDK's requests through your own [OkHttpClient]: `NetworkConfig(sender = ARTrackerOkHttpSender(client))`.
 *
 * The point is to see and control the SDK's own traffic. Add Chucker, a certificate pinner, a proxy or custom timeouts
 * to [client] and they apply to the ingest requests and to the session replay upload (`chunk-url`, the `PUT` to the
 * signed URL, `chunk-ack`) too. Use a client with sensible timeouts: a hung call stalls delivery (the built-in
 * transport uses 10 s to connect and 15 s to read, 30 s to read for the replay upload).
 *
 * Do not add [ARTrackerOkHttpInterceptor] to this client to capture the SDK's own requests: they are tagged and
 * skipped on purpose, otherwise every delivery would be reported as an `http_request` that is delivered again, forever.
 * Redirects must stay off for the same reason the built-in transport never follows them (`followRedirects(false)`).
 *
 * `okhttp3` is a `compileOnly` dependency of the SDK, so this class is only loaded when the host refers to it.
 */
@ExperimentalARTrackerApi
public class ARTrackerOkHttpSender(private val client: OkHttpClient) : ARTrackerSender {

    override fun send(request: SendRequest): SendResponse {
        // OkHttp writes the Content-Type of the body, so it is taken from the request's own header.
        val contentType = request.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?.let { runCatching { it.toMediaType() }.getOrNull() }
            ?: JSON
        val builder = Request.Builder()
            .url(request.url)
            .method(request.method, request.body.toRequestBody(contentType))
            .tag(SdkRequestTag::class.java, SdkRequestTag)
        for ((name, value) in request.headers) builder.header(name, value)
        client.newCall(builder.build()).execute().use { response ->
            val body = response.peekBody(MAX_BODY_BYTES).string().takeIf { it.isNotEmpty() }
            return SendResponse(response.code, response.header("Retry-After"), body)
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_BODY_BYTES = 16L * 1024
    }
}

/** Tag on the SDK's own delivery requests; [ARTrackerOkHttpInterceptor] lets them through without reporting them. */
internal object SdkRequestTag
