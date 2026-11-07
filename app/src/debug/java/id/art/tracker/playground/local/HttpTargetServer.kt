package id.art.tracker.playground.local

import id.art.tracker.playground.settings.EndpointGuard
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Loopback HTTP target for the Network lab (replaces an external echo service): `GET /status/{code}` answers that status,
 * `GET /delay/{seconds}` answers 200 after that many seconds, anything else is 404. IPv4 loopback only.
 *
 * Its port differs from the sink's, so the SDK's OkHttp interceptor records these calls (it skips only the SDK endpoint's
 * own host and port).
 */
class HttpTargetServer(private val requestedPort: Int = EndpointGuard.TARGET_PORT) {
    private var server: MockWebServer? = null

    @Volatile
    var boundAddress: InetAddress? = null
        private set

    val port: Int get() = server?.port ?: requestedPort
    val baseUrl: String get() = "http://localhost:$port"

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        val loopback = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        val candidate = MockWebServer()
        candidate.dispatcher = dispatcher
        return try {
            candidate.start(loopback, requestedPort)
            server = candidate
            boundAddress = loopback
            true
        } catch (_: Throwable) {
            runCatching { candidate.shutdown() }
            false
        }
    }

    @Synchronized
    fun shutdown() {
        runCatching { server?.shutdown() }
        server = null
        boundAddress = null
    }

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val parts = request.path?.substringBefore('?')?.trim('/')?.split('/').orEmpty()
            if (request.method != "GET" || parts.size != 2) return MockResponse().setResponseCode(404)
            val n = parts[1].toIntOrNull() ?: return MockResponse().setResponseCode(404)
            return when (parts[0]) {
                "status" ->
                    if (n in 100..599) MockResponse().setResponseCode(n).setBody("status $n") else MockResponse().setResponseCode(404)
                "delay" ->
                    if (n in 0..60) MockResponse().setResponseCode(200).setHeadersDelay(n.toLong(), TimeUnit.SECONDS).setBody("delayed $n")
                    else MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
}
