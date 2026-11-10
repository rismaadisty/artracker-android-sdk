package id.art.tracker

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import okhttp3.Headers
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A MockWebServer on IPv4 loopback that plays the backend for the instrumented tests: it records every request and
 * answers `202 {"status":"accepted","queued":n}` to ingest unless a response was scripted (scripted responses apply to
 * ingest only). It also plays the three Session Replay API endpoints: the recorded body of a replay `PUT` is the
 * gunzipped JSON array of rrweb events, the `chunk-url` and `chunk-ack` bodies are their plain JSON.
 */
class MockIngest {
    class Recorded(val path: String, val headers: Headers, val body: String, val method: String = "POST")

    private val server = MockWebServer()
    private val recorded = CopyOnWriteArrayList<Recorded>()
    private val script = ConcurrentLinkedQueue<MockResponse>()

    /** What `GET /api/v1/replay/config` answers: screenshot replay stays off unless a test turns it on. */
    @Volatile
    var screenshotEnabled: Boolean = false

    val url: String get() = "http://localhost:${server.port}"
    val port: Int get() = server.port

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val route = path.substringBefore('?')
                val raw = request.body.readByteArray()
                val plain = route == CHUNK_URL_PATH || route == CHUNK_ACK_PATH
                val body = if (plain) {
                    raw.toString(Charsets.UTF_8)
                } else {
                    runCatching {
                        GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrDefault("")
                }
                recorded += Recorded(path, request.headers, body, request.method.orEmpty())
                return when (route) {
                    CHUNK_URL_PATH -> MockResponse().setResponseCode(200).setBody(
                        "{\"upload_url\":\"$url$UPLOAD_PATH?key=k&exp=1&sig=s\",\"object_key\":\"k\",\"expires_in\":300}",
                    )
                    UPLOAD_PATH, CHUNK_ACK_PATH -> MockResponse().setResponseCode(200)
                    CONFIG_PATH -> MockResponse().setResponseCode(200)
                        .setBody("{\"screenshot_enabled\":$screenshotEnabled,\"max_fps\":4}")
                    else -> script.poll()
                        ?: MockResponse().setResponseCode(202).setBody("{\"status\":\"accepted\",\"queued\":1}")
                }
            }
        }
        server.start(InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)), 0)
    }

    /** The next request gets this response (FIFO); afterwards the default 202 applies again. */
    fun enqueue(status: Int, headers: Map<String, String> = emptyMap()) {
        script += MockResponse().setResponseCode(status).apply { headers.forEach { (k, v) -> setHeader(k, v) } }
    }

    fun snapshot(): List<Recorded> = recorded.toList()

    /** Polls until [predicate] holds for the recorded requests or the timeout passes; returns the last snapshot either way. */
    fun awaitUntil(timeoutMs: Long, predicate: (List<Recorded>) -> Boolean): List<Recorded> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val now = snapshot()
            if (predicate(now) || System.nanoTime() > deadline) return now
            Thread.sleep(50)
        }
    }

    /** Waits for at least one request, then returns (and removes) every body recorded so far. */
    fun takeBodies(timeoutMs: Long): List<String> {
        awaitUntil(timeoutMs) { it.isNotEmpty() }
        val taken = snapshot()
        recorded.removeAll(taken.toSet())
        return taken.map { it.body }
    }

    fun clear() {
        recorded.clear()
        script.clear()
        screenshotEnabled = false
    }

    companion object {
        const val CHUNK_URL_PATH = "/api/v1/replay/chunk-url"
        const val UPLOAD_PATH = "/api/v1/replay/upload-chunk"
        const val CHUNK_ACK_PATH = "/api/v1/replay/chunk-ack"
        const val CONFIG_PATH = "/api/v1/replay/config"
    }
}
