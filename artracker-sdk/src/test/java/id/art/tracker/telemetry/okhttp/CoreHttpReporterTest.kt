package id.art.tracker.telemetry.okhttp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.BeforeSend
import id.art.tracker.LogLevel
import id.art.tracker.NetworkConfig
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `CoreHttpReporter` and the interceptor wired to a real (Robolectric) core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreHttpReporterTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
    private lateinit var sdkServer: MockWebServer
    private lateinit var appServer: MockWebServer

    /** While true the app server drops every new connection without answering. */
    @Volatile
    private var dropping = false

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        // Stands in for the ingest backend; answers anything so a flush never hangs.
        sdkServer = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("{}")
            }
            it.start()
        }
        appServer = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                private val drop = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)

                // DISCONNECT_AT_START is decided at accept time from peek(), before any request is read.
                override fun peek(): MockResponse = if (dropping) drop else super.peek()

                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.startsWith("/drop")) {
                        drop
                    } else {
                        MockResponse().setResponseCode(request.path!!.substringAfter("/s/", "200").toIntOrNull() ?: 200)
                            .setBody("hello")
                    }
            }
            it.start()
        }
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        runCatching { sdkServer.shutdown() }
        runCatching { appServer.shutdown() }
    }

    /** The SDK endpoint is addressed as `localhost`; the app server as `127.0.0.1`, so the two hosts differ. */
    private fun init(network: NetworkConfig = NetworkConfig(), endpointHost: String = "localhost") {
        ARTracker.init(
            app,
            ARTrackerConfig(
                endpoint = "http://$endpointHost:${sdkServer.port}",
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
                network = network,
                beforeSend = BeforeSend { it.also(seen::add) },
            ),
        )
    }

    private fun appUrl(path: String, host: String = "127.0.0.1"): HttpUrl =
        HttpUrl.Builder().scheme("http").host(host).port(appServer.port)
            .encodedPath(path.substringBefore('?'))
            .apply { if ('?' in path) encodedQuery(path.substringAfter('?')) }
            .build()

    private fun call(url: HttpUrl, interceptor: ARTrackerOkHttpInterceptor = ARTrackerOkHttpInterceptor()): String? =
        OkHttpClient.Builder().addInterceptor(interceptor).retryOnConnectionFailure(false).build()
            .newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }

    private fun settle(): List<TelemetryItem> {
        runBlocking { withTimeout(15_000) { ARTrackerCore.instanceOrNull()!!.awaitIdle() } }
        return seen.filter { it.name == "http_request" }
    }

    private fun record(status: Int? = 200) =
        HttpRecord("GET", "http://127.0.0.1/x", "127.0.0.1", status, 12, null, null, "http/1.1", null)

    @Test
    fun realCall_createsHttpItemAndBreadcrumb() {
        init()

        val body = call(appUrl("/hello?token=SECRET123&page=2"))

        assertEquals("hello", body)
        val item = settle().single()
        assertEquals(LogLevel.INFO, item.level)
        assertEquals("GET /hello -> 200", item.message)
        val a = item.attributes
        assertEquals("GET", a["method"])
        assertEquals(200, a["status"])
        assertEquals("127.0.0.1", a["host"])
        assertEquals("http/1.1", a["protocol"])
        assertEquals(5L, a["res_bytes"])
        assertNotNull(a["duration_ms"])
        assertFalse(a.containsKey("error"))
        val url = a["url"] as String
        assertFalse(url, url.contains("SECRET123"))
        assertTrue(url, url.contains("token=[redacted]"))

        val crumb = ARTrackerCore.instanceOrNull()!!.breadcrumbs.snapshot().single { it["type"] == "http" }
        assertEquals("GET /hello -> 200", crumb["message"])
    }

    @Test
    fun message_containsPathOnly_noQuery() {
        init()
        call(appUrl("/a/b?q=secretish"))
        val item = settle().single()
        assertEquals("GET /a/b -> 200", item.message)
        assertFalse(item.message!!.contains("q="))
    }

    @Test
    fun failedCall_reportsErrorAttributeAndNoStatus() {
        init()

        val thrown = runCatching { dropping = true; call(appUrl("/drop")) }.exceptionOrNull()

        assertNotNull(thrown)
        val item = settle().single()
        assertEquals(LogLevel.ERROR, item.level)
        assertNull(item.attributes["status"])
        assertTrue(item.attributes["error"] is String)
        assertTrue(item.message!!.startsWith("GET /drop -> "))
    }

    @Test
    fun ignoredHost_notReported() {
        init(NetworkConfig(ignoreHosts = setOf("127.0.0.1")))

        call(appUrl("/hello"))

        assertTrue(settle().isEmpty())
        assertTrue(ARTrackerCore.instanceOrNull()!!.breadcrumbs.snapshot().none { it["type"] == "http" })
    }

    @Test
    fun ignoredHost_matchIsCaseInsensitive_andExactOnly() {
        init(NetworkConfig(ignoreHosts = setOf("LOCALHOST", "example.com")))

        call(appUrl("/a", host = "localhost"))
        call(appUrl("/b")) // 127.0.0.1 is not in the list

        val items = settle()
        assertEquals(listOf("GET /b -> 200"), items.map { it.message })
    }

    @Test
    fun sdkEndpoint_sameHostAndPort_notReported() {
        init(endpointHost = "127.0.0.1")

        // The app server is the one at the endpoint's port: same host + same port.
        val url = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(sdkServer.port).encodedPath("/x").build()
        call(url)

        assertTrue(settle().isEmpty())
    }

    @Test
    fun sdkEndpoint_sameHostDifferentPort_isReported() {
        init(endpointHost = "127.0.0.1")

        call(appUrl("/hello")) // 127.0.0.1 but the app server's port

        assertEquals(listOf("GET /hello -> 200"), settle().map { it.message })
    }

    @Test
    fun sdkEndpoint_impliedPortFromScheme_isIgnored() {
        ARTracker.init(
            app,
            ARTrackerConfig(
                endpoint = "https://api.sdk-test.invalid",
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
                beforeSend = BeforeSend { it.also(seen::add) },
            ),
        )
        val reporter = CoreHttpReporter()

        reporter.report(record(200).copy(url = "https://api.sdk-test.invalid/v1/x", host = "api.sdk-test.invalid"))
        reporter.report(record(200).copy(url = "https://API.sdk-test.invalid:443/v1/y", host = "api.sdk-test.invalid"))
        reporter.report(record(200).copy(url = "http://api.sdk-test.invalid/v1/z", host = "api.sdk-test.invalid"))
        reporter.report(record(200).copy(url = "https://api.sdk-test.invalid:8443/v1/w", host = "api.sdk-test.invalid"))

        assertEquals(
            listOf("GET /v1/z -> 200", "GET /v1/w -> 200"),
            settle().map { it.message },
        )
    }

    @Test
    fun sdkEndpointHost_matchIsCaseInsensitive() {
        init(endpointHost = "LocalHost")

        call(HttpUrl.Builder().scheme("http").host("localhost").port(sdkServer.port).encodedPath("/hello").build())

        assertTrue(settle().isEmpty())
    }

    @Test
    fun noCore_isNoOp() {
        ARTracker.resetForTest()
        assertNull(ARTrackerCore.instanceOrNull())

        val body = call(appUrl("/hello"))

        assertEquals("hello", body)
    }

    @Test
    fun noCore_ioException_stillPropagates() {
        assertNull(ARTrackerCore.instanceOrNull())

        val thrown = runCatching { dropping = true; call(appUrl("/drop")) }.exceptionOrNull()

        assertTrue(thrown is java.io.IOException)
    }

    @Test
    fun optedOut_isNoOp() {
        init()
        ARTracker.optOut(true)

        call(appUrl("/hello"))

        assertTrue(settle().isEmpty())
        assertTrue(ARTrackerCore.instanceOrNull()!!.breadcrumbs.snapshot().none { it["type"] == "http" })
    }

    @Test
    fun captureOnlyErrors_dropsSuccess_keepsErrors() {
        init(NetworkConfig(captureOnlyErrors = true))
        val reporter = CoreHttpReporter()

        reporter.report(record(200))
        reporter.report(record(302))
        reporter.report(record(404))
        reporter.report(record(500))
        reporter.report(record(null).copy(error = "java.io.IOException: x"))

        assertEquals(listOf(404, 500, null), settle().map { it.attributes["status"] })
    }

    @Test
    fun sampling_usesInjectedRandom() {
        init(NetworkConfig(sampleRate = 0.3))
        var next = 0.0
        val reporter = CoreHttpReporter(random = { next })

        next = 0.1
        reporter.report(record(200).copy(url = "http://127.0.0.1/kept"))
        next = 0.3
        reporter.report(record(200).copy(url = "http://127.0.0.1/dropped-edge"))
        next = 0.9
        reporter.report(record(200).copy(url = "http://127.0.0.1/dropped"))

        assertEquals(listOf("GET /kept -> 200"), settle().map { it.message })
    }

    @Test
    fun sampleRateZero_dropsAll_andOneKeepsAll() {
        init(NetworkConfig(sampleRate = 0.0))
        CoreHttpReporter(random = { 0.0 }).report(record(200))
        assertTrue(settle().isEmpty())

        ARTracker.resetForTest()
        seen.clear()
        init(NetworkConfig(sampleRate = 1.0))
        CoreHttpReporter(random = { 0.999999 }).report(record(200))
        assertEquals(1, settle().size)
    }

    @Test
    fun sampledOutRequest_stillLeavesBreadcrumb() {
        init(NetworkConfig(sampleRate = 0.0))

        CoreHttpReporter(random = { 0.5 }).report(record(200))

        assertTrue(settle().isEmpty())
        assertEquals(1, ARTrackerCore.instanceOrNull()!!.breadcrumbs.snapshot().count { it["type"] == "http" })
    }

    @Test
    fun errorAttribute_isTrimmedAndRedacted() {
        init()
        val long = "x".repeat(1000)

        CoreHttpReporter().report(
            record(null).copy(error = "java.io.IOException: failed for a@b.co $long"),
        )

        val err = settle().single().attributes["error"] as String
        assertFalse(err, err.contains("a@b.co"))
        assertTrue(err.length <= 300)
    }

    @Test
    fun reporter_withoutCore_doesNothing() {
        assertNull(ARTrackerCore.instanceOrNull())
        CoreHttpReporter().report(record(200)) // must not throw
    }
}
