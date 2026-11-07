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
 * A MockWebServer on IPv4 loopback that plays the ingest backend for the instrumented tests: it records every request
 * (body already gunzipped) and answers `202 {"status":"accepted","queued":n}` unless a response was scripted.
 */
class MockIngest {
    class Recorded(val path: String, val headers: Headers, val body: String)

    private val server = MockWebServer()
    private val recorded = CopyOnWriteArrayList<Recorded>()
    private val script = ConcurrentLinkedQueue<MockResponse>()

    val url: String get() = "http://localhost:${server.port}"
    val port: Int get() = server.port

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = runCatching {
                    GZIPInputStream(ByteArrayInputStream(request.body.readByteArray())).use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrDefault("")
                recorded += Recorded(request.path.orEmpty(), request.headers, body)
                return script.poll() ?: MockResponse().setResponseCode(202).setBody("{\"status\":\"accepted\",\"queued\":1}")
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
    }
}
