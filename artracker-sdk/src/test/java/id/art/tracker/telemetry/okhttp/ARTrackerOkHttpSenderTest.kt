package id.art.tracker.telemetry.okhttp

import id.art.tracker.SendRequest
import id.art.tracker.testutil.FakeClock
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ARTrackerOkHttpSenderTest {
    private lateinit var server: MockWebServer
    private val key = "art_live_" + "a".repeat(43)
    private val body = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 42)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun ingestRequest() = SendRequest(
        url = server.url("/api/v1/ingest").toString(),
        headers = linkedMapOf(
            "X-Artracker-Key" to key,
            "Content-Type" to "application/json",
            "Content-Encoding" to "gzip",
            "User-Agent" to "artracker-android/test",
        ),
        body = body,
    )

    private fun client(vararg interceptors: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .also { builder -> interceptors.forEach { builder.addInterceptor(it) } }
            .build()

    @Test
    fun posts_theBodyAndHeadersUnchanged() {
        server.enqueue(MockResponse().setResponseCode(202))

        val response = ARTrackerOkHttpSender(client()).send(ingestRequest())

        assertEquals(202, response.statusCode)
        assertNull(response.retryAfter)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/ingest", recorded.path)
        assertEquals(key, recorded.getHeader("X-Artracker-Key"))
        assertEquals("gzip", recorded.getHeader("Content-Encoding"))
        assertEquals("artracker-android/test", recorded.getHeader("User-Agent"))
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertArrayEquals(body, recorded.body.readByteArray())
    }

    @Test
    fun retryAfter_isExposedAsTheRawHeader() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))

        val response = ARTrackerOkHttpSender(client()).send(ingestRequest())

        assertEquals(429, response.statusCode)
        assertEquals("7", response.retryAfter)
    }

    @Test
    fun errorStatuses_areReturned_notThrown() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(503, ARTrackerOkHttpSender(client()).send(ingestRequest()).statusCode)
    }

    @Test
    fun aNetworkFailure_isAnIOException() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        try {
            ARTrackerOkHttpSender(client()).send(ingestRequest())
            fail("a dropped connection must surface as an IOException")
        } catch (e: IOException) {
            // expected: the SDK maps it to a retry with backoff
        }
    }

    @Test
    fun interceptorsOnTheClient_seeTheDeliveries() {
        // This is what makes Chucker (an application interceptor) show the SDK's own requests.
        val seen = CopyOnWriteArrayList<Request>()
        val spy = Interceptor { chain -> chain.proceed(chain.request().also(seen::add)) }
        server.enqueue(MockResponse().setResponseCode(200))

        ARTrackerOkHttpSender(client(spy)).send(ingestRequest())

        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/ingest", request.url.encodedPath)
        assertEquals("gzip", request.header("Content-Encoding"))
    }

    @Test
    fun theSdkInterceptor_doesNotReportTheSdksOwnDeliveries() {
        val records = CopyOnWriteArrayList<HttpRecord>()
        val client = client(ARTrackerOkHttpInterceptor(HttpReporter { records.add(it) }, FakeClock()))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        ARTrackerOkHttpSender(client).send(ingestRequest())
        assertTrue("the delivery must not be reported (it would feed itself forever)", records.isEmpty())

        client.newCall(Request.Builder().url(server.url("/other")).build()).execute().use { }
        assertEquals("an ordinary request on the same client is still reported", 1, records.size)
        assertTrue(records.single().url.endsWith("/other"))
    }
}
