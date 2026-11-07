package id.art.tracker.playground.local

import id.art.tracker.playground.inspector.EnvelopeParser
import id.art.tracker.playground.inspector.EventRing
import id.art.tracker.playground.inspector.Inspector
import id.art.tracker.playground.settings.EndpointGuard
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.zip.GZIPInputStream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Loopback ingest endpoint for debug builds (MockWebServer). The SDK posts here exactly as it would to the backend; what
 * arrives is already redacted, so showing it in the Inspector exposes nothing the backend would not receive.
 *
 * Binds IPv4 loopback only (never reachable from other devices). The `X-Artracker-Key` header is never read into any
 * stored structure. Bodies above [MAX_UNCOMPRESSED] bytes are refused with 413 and not parsed.
 */
class InAppSink(
    private val requestedPort: Int = EndpointGuard.SINK_PORT,
    private val ring: EventRing = Inspector.ring,
) {
    private var server: MockWebServer? = null
    private val vault = ArrayDeque<String>()

    @Volatile
    var boundAddress: InetAddress? = null
        private set

    val port: Int get() = server?.port ?: requestedPort

    /** False when the port is busy; never throws. Idempotent. */
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

    /** Writes the last [MAX_CHUNKS] replay-chunk attribute objects (masked, intact) as `replay-chunks.json` in [dir]. */
    fun exportReplayChunks(dir: File): File? = runCatching {
        dir.mkdirs()
        val snapshot = synchronized(vault) { vault.toList() }
        File(dir, "replay-chunks.json").also { it.writeText(snapshot.joinToString(",", "[", "]")) }
    }.getOrNull()

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path?.substringBefore('?')
            if (request.method != "POST" || path != INGEST_PATH) return MockResponse().setResponseCode(404)
            return when (val result = gunzip(request.body.readByteArray())) {
                is Gunzip.Corrupt -> MockResponse().setResponseCode(400)
                is Gunzip.TooLarge -> MockResponse().setResponseCode(413)
                is Gunzip.Ok -> {
                    val items = EnvelopeParser.parse(result.text)
                    ring.addAll(items)
                    EnvelopeParser.replayChunkAttributes(result.text).forEach(::keepChunk)
                    MockResponse()
                        .setResponseCode(202)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"status\":\"accepted\",\"queued\":${items.size}}")
                }
            }
        }
    }

    private fun keepChunk(attributesJson: String) {
        synchronized(vault) {
            vault.addLast(attributesJson)
            while (vault.size > MAX_CHUNKS) vault.removeFirst()
        }
    }

    private sealed interface Gunzip {
        class Ok(val text: String) : Gunzip
        object Corrupt : Gunzip
        object TooLarge : Gunzip
    }

    private fun gunzip(bytes: ByteArray): Gunzip = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_UNCOMPRESSED) return Gunzip.TooLarge
                out.write(buffer, 0, n)
            }
            Gunzip.Ok(out.toString("UTF-8"))
        }
    } catch (_: IOException) {
        Gunzip.Corrupt
    }

    private companion object {
        const val INGEST_PATH = "/api/v1/ingest"
        const val MAX_UNCOMPRESSED = 4 * 1024 * 1024
        const val MAX_CHUNKS = 50
    }
}
