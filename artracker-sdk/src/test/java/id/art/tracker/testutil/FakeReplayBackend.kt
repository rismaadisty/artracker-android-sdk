package id.art.tracker.testutil

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

/** One request the fake backend received, with the raw body. */
internal class RecordedCall(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    /** Path without the query string. */
    val route: String get() = path.substringBefore('?')

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun json(): JSONObject = JSONObject(body.toString(Charsets.UTF_8))
}

/**
 * MockWebServer that imitates the three Session Replay API endpoints: `POST chunk-url`, `PUT upload-chunk` and
 * `POST chunk-ack`, plus `POST ingest` so a whole SDK can talk to it. Every request is recorded. Each step answers
 * `200` (`202` for ingest) unless its responder is replaced.
 */
internal class FakeReplayBackend {
    private val server = MockWebServer()
    private val recorded = CopyOnWriteArrayList<RecordedCall>()

    /** Base URL without a trailing slash: what the SDK config calls `endpoint`. */
    val endpoint: String get() = server.url("/").toString().trimEnd('/')

    val requests: List<RecordedCall> get() = recorded.toList()

    @Volatile var chunkUrl: (RecordedCall) -> MockResponse = { call ->
        val body = call.json()
        val key = "${body.optString("session_id")}-${body.optInt("chunk_seq")}"
        MockResponse().setResponseCode(200).setBody(
            """{"upload_url":"$endpoint/api/v1/replay/upload-chunk?key=$key&exp=1&sig=s","object_key":"$key","expires_in":300}""",
        )
    }

    @Volatile var upload: (RecordedCall) -> MockResponse = { MockResponse().setResponseCode(200) }

    @Volatile var ack: (RecordedCall) -> MockResponse = { MockResponse().setResponseCode(200) }

    @Volatile var ingest: (RecordedCall) -> MockResponse = { MockResponse().setResponseCode(202) }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val call = RecordedCall(
                    method = request.method.orEmpty(),
                    path = request.path.orEmpty(),
                    headers = request.headers.toMultimap().mapValues { it.value.joinToString(",") },
                    body = request.body.clone().readByteArray(),
                )
                recorded += call
                return when {
                    call.method == "POST" && call.route == CHUNK_URL -> chunkUrl(call)
                    call.method == "PUT" && call.route == UPLOAD -> upload(call)
                    call.method == "POST" && call.route == ACK -> ack(call)
                    call.method == "POST" && call.route == INGEST -> ingest(call)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    fun requestsTo(route: String): List<RecordedCall> = requests.filter { it.route == route }

    fun shutdown() {
        runCatching { server.shutdown() }
    }

    companion object {
        const val CHUNK_URL = "/api/v1/replay/chunk-url"
        const val UPLOAD = "/api/v1/replay/upload-chunk"
        const val ACK = "/api/v1/replay/chunk-ack"
        const val INGEST = "/api/v1/ingest"
    }
}
