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
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Loopback ingest endpoint for debug builds (MockWebServer). The SDK posts here exactly as it would to the backend; what
 * arrives is already redacted, so showing it in the Inspector exposes nothing the backend would not receive.
 *
 * It also plays the Session Replay API (`chunk-url`, `upload-chunk`, `chunk-ack`): the `upload_url` it hands out points
 * back at itself, and each uploaded batch is kept for the export. Those uploads are not ingest items, so the Inspector
 * does not list them.
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

    /** `object_key` -> `chunk_seq` from `chunk-url`, so the PUT knows which chunk it carries. */
    private val uploadKeys = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > MAX_UPLOAD_KEYS
    }

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

    /**
     * Writes the last [MAX_CHUNKS] replay chunks (masked, intact) as `replay-chunks.json` in [dir], one attribute object
     * `{seq, start, end, full_snapshot, platform, events}` each, the format `tools/replay-viewer` reads.
     */
    fun exportReplayChunks(dir: File): File? = runCatching {
        dir.mkdirs()
        val snapshot = synchronized(vault) { vault.toList() }
        File(dir, "replay-chunks.json").also { it.writeText(snapshot.joinToString(",", "[", "]")) }
    }.getOrNull()

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path?.substringBefore('?')
            return when {
                request.method == "POST" && path == INGEST_PATH -> ingest(request)
                request.method == "POST" && path == CHUNK_URL_PATH -> chunkUrl(request)
                request.method == "PUT" && path == UPLOAD_PATH -> upload(request)
                request.method == "POST" && path == CHUNK_ACK_PATH -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"status\":\"acknowledged\"}")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun ingest(request: RecordedRequest): MockResponse =
        when (val result = gunzip(request.body.readByteArray())) {
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

    /** Remembers the `chunk_seq` under an `object_key` and answers with an `upload_url` that points back here. */
    private fun chunkUrl(request: RecordedRequest): MockResponse {
        val body = request.body.readByteArray()
        if (body.size > MAX_CONTROL_BODY) return MockResponse().setResponseCode(413)
        val json = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return MockResponse().setResponseCode(400)
        val seq = json.optInt("chunk_seq", -1)
        val session = json.optString("session_id")
        if (seq < 0 || !SAFE_TOKEN.matches(session)) return MockResponse().setResponseCode(400)
        val key = "$session-$seq"
        synchronized(uploadKeys) { uploadKeys[key] = seq }
        // Only the request's own host:port is reused, and only when it looks like one.
        val host = request.getHeader("Host")?.takeIf { SAFE_HOST.matches(it) } ?: "localhost:$port"
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"upload_url\":\"http://$host$UPLOAD_PATH?key=$key&exp=1&sig=local\"," +
                    "\"object_key\":\"$key\",\"expires_in\":300}",
            )
    }

    /** The PUT body is the gzip of a JSON array of rrweb events: stored as one chunk in the export shape. */
    private fun upload(request: RecordedRequest): MockResponse {
        val text = when (val result = gunzip(request.body.readByteArray())) {
            is Gunzip.Corrupt -> return MockResponse().setResponseCode(400)
            is Gunzip.TooLarge -> return MockResponse().setResponseCode(413)
            is Gunzip.Ok -> result.text
        }
        val events = runCatching { JSONArray(text) }.getOrNull() ?: return MockResponse().setResponseCode(400)
        val key = request.requestUrl?.queryParameter("key").orEmpty()
        val seq = synchronized(uploadKeys) { uploadKeys[key] } ?: return MockResponse().setResponseCode(404)
        var start = Long.MAX_VALUE
        var end = Long.MIN_VALUE
        var full = false
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optInt("type") == EVENT_FULL_SNAPSHOT) full = true
            val ts = event.optLong("timestamp", -1)
            if (ts >= 0) {
                start = minOf(start, ts)
                end = maxOf(end, ts)
            }
        }
        if (start == Long.MAX_VALUE) {
            start = 0
            end = 0
        }
        keepChunk(
            "{\"seq\":$seq,\"start\":$start,\"end\":$end,\"full_snapshot\":$full," +
                "\"platform\":\"android\",\"events\":$events}",
        )
        return MockResponse().setResponseCode(200)
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
        const val CHUNK_URL_PATH = "/api/v1/replay/chunk-url"
        const val UPLOAD_PATH = "/api/v1/replay/upload-chunk"
        const val CHUNK_ACK_PATH = "/api/v1/replay/chunk-ack"
        const val MAX_UNCOMPRESSED = 4 * 1024 * 1024
        const val MAX_CONTROL_BODY = 16 * 1024
        const val MAX_CHUNKS = 50
        const val MAX_UPLOAD_KEYS = 200
        const val EVENT_FULL_SNAPSHOT = 2
        val SAFE_TOKEN = Regex("^[a-zA-Z0-9_-]{8,128}$")
        val SAFE_HOST = Regex("^[a-zA-Z0-9.\\-]{1,64}:[0-9]{1,5}$")
    }
}
