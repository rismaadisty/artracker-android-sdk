package id.art.tracker.transport

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IngestClientTest {
    private lateinit var server: MockWebServer
    private val body = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client(endpoint: String = server.url("/").toString().trimEnd('/')) =
        IngestClient(endpoint, "art_live_testkey")

    private fun send(status: Int, vararg headers: Pair<String, String>): SendResult {
        server.enqueue(MockResponse().setResponseCode(status).also { r -> headers.forEach { r.addHeader(it.first, it.second) } })
        return client().send(body)
    }

    @Test
    fun sendsRequiredHeaders() {
        server.enqueue(MockResponse().setResponseCode(202))
        client().send(body)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/api/v1/ingest", req.path)
        assertEquals("art_live_testkey", req.getHeader("X-Artracker-Key"))
        assertEquals("gzip", req.getHeader("Content-Encoding"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(req.getHeader("User-Agent")!!.startsWith("artracker-android/"))
        assertArrayEquals(body, req.body.readByteArray())
    }

    @Test
    fun endpointWithTrailingSlash_noDoubleSlash() {
        server.enqueue(MockResponse().setResponseCode(202))
        val result = client(server.url("/").toString()).send(body)
        assertEquals(SendResult.Accepted, result)
        assertEquals("/api/v1/ingest", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun endpointWithBasePath_isPreserved() {
        server.enqueue(MockResponse().setResponseCode(202))
        client(server.url("/proxy/").toString()).send(body)
        assertEquals("/proxy/api/v1/ingest", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test fun status202_accepted() = assertEquals(SendResult.Accepted, send(202))
    @Test fun status200_accepted() = assertEquals(SendResult.Accepted, send(200))
    @Test fun status400_drop() = assertEquals(SendResult.Drop(400), send(400))
    @Test fun status404_drop() = assertEquals(SendResult.Drop(404), send(404))
    @Test fun status401_unauthorized() = assertEquals(SendResult.Unauthorized, send(401))
    @Test fun status403_unauthorized() = assertEquals(SendResult.Unauthorized, send(403))
    @Test fun status413_tooLarge() = assertEquals(SendResult.TooLarge, send(413))
    @Test fun status500_retryNoDelay() = assertEquals(SendResult.RetryAfter(null), send(500))
    @Test fun status502_retryNoDelay() = assertEquals(SendResult.RetryAfter(null), send(502))

    @Test fun status429_withRetryAfter() = assertEquals(SendResult.RetryAfter(7_000), send(429, "Retry-After" to "7"))
    @Test fun status503_withRetryAfter() = assertEquals(SendResult.RetryAfter(5_000), send(503, "Retry-After" to "5"))
    @Test fun status429_withoutHeader() = assertEquals(SendResult.RetryAfter(null), send(429))
    @Test fun status503_withoutHeader() = assertEquals(SendResult.RetryAfter(null), send(503))

    @Test fun retryAfter_httpDate_isNull() =
        assertEquals(SendResult.RetryAfter(null), send(429, "Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT"))

    @Test fun retryAfter_garbage_isNull() = assertEquals(SendResult.RetryAfter(null), send(429, "Retry-After" to "soon"))
    @Test fun retryAfter_negative_isNull() = assertEquals(SendResult.RetryAfter(null), send(429, "Retry-After" to "-5"))
    @Test fun retryAfter_zero_isZero() = assertEquals(SendResult.RetryAfter(0), send(429, "Retry-After" to "0"))

    @Test fun retryAfter_huge_isCappedAtOneHour() =
        assertEquals(SendResult.RetryAfter(3_600_000), send(429, "Retry-After" to "999999999"))

    @Test fun retryAfter_overflowingNumber_isCappedAtOneHour() =
        assertEquals(SendResult.RetryAfter(3_600_000), send(503, "Retry-After" to "99999999999999999999999"))

    @Test
    fun redirect_isNotFollowed() {
        val other = MockWebServer().also { it.start() }
        try {
            other.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/api/v1/ingest").toString()))
            val result = client().send(body)
            assertEquals(0, other.requestCount)
            assertEquals(SendResult.RetryAfter(null), result)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun serverDown_isNetworkError() {
        val c = client()
        server.shutdown()
        val result = c.send(body)
        assertTrue("was $result", result is SendResult.NetworkError)
    }

    @Test
    fun readTimeout_isNetworkError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c = IngestClient(server.url("/").toString().trimEnd('/'), "art_live_testkey", connectTimeoutMs = 500, readTimeoutMs = 300)
        val result = c.send(body)
        assertTrue("was $result", result is SendResult.NetworkError)
    }

    @Test
    fun malformedEndpoint_isDropNotThrown() {
        assertEquals(SendResult.Drop(0), IngestClient("not a url", "art_live_testkey").send(body))
        assertEquals(SendResult.Drop(0), IngestClient("", "art_live_testkey").send(body))
    }

    @Test
    fun errorBodyIsDrained_connectionReused() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("x".repeat(4096)))
        server.enqueue(MockResponse().setResponseCode(202))
        val c = client()
        assertEquals(SendResult.Drop(400), c.send(body))
        assertEquals(SendResult.Accepted, c.send(body))
        assertEquals(0, server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
        assertEquals(1, server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
    }
}
