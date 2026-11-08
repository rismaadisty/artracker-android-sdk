package id.art.tracker.telemetry.okhttp

import id.art.tracker.LogLevel
import id.art.tracker.testutil.FakeClock
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The interceptor itself, with a recording [HttpReporter]; the core side is covered by `CoreHttpReporterTest`. */
class ARTrackerOkHttpInterceptorTest {
    private val records = CopyOnWriteArrayList<HttpRecord>()
    private val clock = FakeClock()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder()
            .addInterceptor(ARTrackerOkHttpInterceptor(HttpReporter { records.add(it) }, clock))
            .build()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun get(path: String) = Request.Builder().url(server.url(path)).build()

    @Test
    fun success_reportsInfo() {
        server.enqueue(MockResponse().setBody("hello").setResponseCode(200))

        client.newCall(get("/ok")).execute().use { }

        val r = records.single()
        assertEquals("GET", r.method)
        assertEquals(200, r.status)
        assertEquals(LogLevel.INFO, r.level())
        assertEquals(server.hostName, r.host)
        assertTrue(r.url.endsWith("/ok"))
        assertEquals(5L, r.responseBytes)
        assertNull(r.requestBytes)
        assertEquals("http/1.1", r.protocol)
        assertNull(r.error)
    }

    @Test
    fun postBody_reportsRequestBytes() {
        server.enqueue(MockResponse().setResponseCode(201))
        val body = "abcd".toRequestBody()

        client.newCall(Request.Builder().url(server.url("/p")).post(body).build()).execute().use { }

        val r = records.single()
        assertEquals("POST", r.method)
        assertEquals(4L, r.requestBytes)
    }

    @Test
    fun notFound_reportsWarn() {
        server.enqueue(MockResponse().setResponseCode(404))

        client.newCall(get("/missing")).execute().use { }

        val r = records.single()
        assertEquals(404, r.status)
        assertEquals(LogLevel.WARN, r.level())
    }

    @Test
    fun serverError_reportsError() {
        server.enqueue(MockResponse().setResponseCode(503))

        client.newCall(get("/boom")).execute().use { }

        val r = records.single()
        assertEquals(503, r.status)
        assertEquals(LogLevel.ERROR, r.level())
    }

    @Test
    fun ioException_isRethrown_andReported() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val thrown = CopyOnWriteArrayList<Throwable>()
        // Same instance check: a second interceptor sits outside ours and records what reaches it.
        val outer = OkHttpClient.Builder()
            .addInterceptor { chain ->
                try {
                    chain.proceed(chain.request())
                } catch (t: Throwable) {
                    thrown += t
                    throw t
                }
            }
            .addInterceptor(ARTrackerOkHttpInterceptor(HttpReporter { records.add(it) }, clock))
            .retryOnConnectionFailure(false)
            .build()
        // Order: the recorder above is outermost, ours is inside it, so `thrown` holds what ours rethrew.

        try {
            outer.newCall(get("/drop")).execute()
            fail("expected an IOException")
        } catch (e: IOException) {
            assertSame(thrown.single(), e)
        }

        val r = records.single()
        assertNull(r.status)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("IOException") || r.error!!.contains("Exception"))
        assertEquals(LogLevel.ERROR, r.level())
        assertNull(r.protocol)
    }

    @Test
    fun anyThrowable_isReportedAndRethrownUnchanged() {
        val boom = IllegalStateException("inner")
        val c = OkHttpClient.Builder()
            .addInterceptor(ARTrackerOkHttpInterceptor(HttpReporter { records.add(it) }, clock))
            .addInterceptor { throw boom }
            .build()

        try {
            c.newCall(get("/x")).execute()
            fail("expected the same exception")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }

        val r = records.single()
        assertNull(r.status)
        assertEquals("java.lang.IllegalStateException: inner", r.error)
    }

    @Test
    fun responseBody_stillReadableByCaller() {
        server.enqueue(MockResponse().setBody("hello"))

        val text = client.newCall(get("/body")).execute().use { it.body!!.string() }

        assertEquals("hello", text)
        assertEquals(5L, records.single().responseBytes)
    }

    @Test
    fun unknownLength_reportsNullResponseBytes() {
        server.enqueue(MockResponse().setChunkedBody("hello", 2))

        val text = client.newCall(get("/chunked")).execute().use { it.body!!.string() }

        assertEquals("hello", text)
        assertNull(records.single().responseBytes)
    }

    @Test
    fun sensitiveQuery_isScrubbed() {
        server.enqueue(MockResponse())

        client.newCall(get("/cb?token=SECRET123&page=2&email=a@b.co")).execute().use { }

        val url = records.single().url
        assertFalse(url, url.contains("SECRET123"))
        assertFalse(url, url.contains("a@b.co"))
        assertTrue(url, url.contains("token=[redacted]"))
        assertTrue(url, url.contains("page=2"))
    }

    @Test
    fun urlCredentials_areNeverReported() {
        server.enqueue(MockResponse())
        val url = server.url("/c").newBuilder().username("alice").password("hunter2").build()

        client.newCall(Request.Builder().url(url).build()).execute().use { }

        val reported = records.single().url
        assertFalse(reported, reported.contains("hunter2"))
        assertFalse(reported, reported.contains("alice"))
    }

    @Test
    fun duration_usesInjectedClock() {
        server.enqueue(MockResponse())
        val c = OkHttpClient.Builder()
            .addInterceptor(ARTrackerOkHttpInterceptor(HttpReporter { records.add(it) }, clock))
            .addInterceptor { chain -> clock.advance(250); chain.proceed(chain.request()) }
            .build()

        c.newCall(get("/slow")).execute().use { }

        assertEquals(250L, records.single().durationMs)
    }

    @Test
    fun reporterThatThrows_neverBreaksTheRequest() {
        server.enqueue(MockResponse().setBody("hello"))
        val c = OkHttpClient.Builder()
            .addInterceptor(ARTrackerOkHttpInterceptor(HttpReporter { error("reporter bug") }, clock))
            .build()

        val text = c.newCall(get("/safe")).execute().use { it.body!!.string() }

        assertEquals("hello", text)
    }

    @Test
    fun reporterThatThrows_doesNotMaskTheOriginalIOException() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val c = OkHttpClient.Builder()
            .addInterceptor(ARTrackerOkHttpInterceptor(HttpReporter { error("reporter bug") }, clock))
            .retryOnConnectionFailure(false)
            .build()

        try {
            c.newCall(get("/drop")).execute()
            fail("expected an IOException")
        } catch (e: IOException) {
            // The caller sees the network failure, never the reporter's IllegalStateException.
        }
    }

    @Test
    fun level_mapping() {
        fun rec(status: Int?) = HttpRecord("GET", "http://h/", "h", status, 1, null, null, null, null)
        assertEquals(LogLevel.INFO, rec(200).level())
        assertEquals(LogLevel.INFO, rec(302).level())
        assertEquals(LogLevel.WARN, rec(400).level())
        assertEquals(LogLevel.WARN, rec(499).level())
        assertEquals(LogLevel.ERROR, rec(500).level())
        assertEquals(LogLevel.ERROR, rec(null).level())
    }
}
