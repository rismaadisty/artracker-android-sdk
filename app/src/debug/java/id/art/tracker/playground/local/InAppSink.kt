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
 * back at itself, and the screenshot frames of each uploaded chunk are kept for the export. Those uploads are not
 * ingest items, so the Inspector does not list them.
 *
 * Binds IPv4 loopback only (never reachable from other devices). The `X-Artracker-Key` header is never read into any
 * stored structure. Bodies above [MAX_UNCOMPRESSED] bytes are refused with 413 and not parsed.
 */
class InAppSink(
    private val requestedPort: Int = EndpointGuard.SINK_PORT,
    private val ring: EventRing = Inspector.ring,
) {
    private var server: MockWebServer? = null

    /** `object_key` -> `chunk_seq` from `chunk-url`, so the PUT knows which chunk it carries. */
    private val uploadKeys = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > MAX_UPLOAD_KEYS
    }

    /** When false a chunk is refused with `403 screenshot_disabled` (what a project with screenshot replay off answers). */
    @Volatile
    var screenshotEnabled: Boolean = true

    /** What the screenshot uploads added up to since the sink started. */
    class ScreenshotStats(val chunks: Int, val frames: Int, val touches: Int, val bytes: Long)

    private class Shot(val ts: Long, val jpeg: ByteArray)

    private val shots = ArrayDeque<Shot>()
    private var shotChunks = 0
    private var shotFrames = 0
    private var shotTouches = 0
    private var shotBytes = 0L

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
        if (!screenshotEnabled) {
            return MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"screenshot_disabled\"}")
        }
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

    /** The PUT body is the gzip of an `artracker-screen-v1` document. */
    private fun upload(request: RecordedRequest): MockResponse {
        val text = when (val result = gunzip(request.body.readByteArray())) {
            is Gunzip.Corrupt -> return MockResponse().setResponseCode(400)
            is Gunzip.TooLarge -> return MockResponse().setResponseCode(413)
            is Gunzip.Ok -> result.text
        }
        val key = request.requestUrl?.queryParameter("key").orEmpty()
        val seq = synchronized(uploadKeys) { uploadKeys[key] } ?: return MockResponse().setResponseCode(404)
        if (key.isEmpty() || synchronized(uploadKeys) { uploadKeys[key] } == null) return MockResponse().setResponseCode(404)
        val screen = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("format") == FORMAT_SCREEN }
            ?: return MockResponse().setResponseCode(400)
        return keepScreenChunk(screen, text.length)
    }

    /** An `artracker-screen-v1` chunk: counted, and its JPEG frames kept (the last [MAX_SHOTS]). */
    private fun keepScreenChunk(chunk: JSONObject, size: Int): MockResponse {
        val frames = chunk.optJSONArray("frames") ?: return MockResponse().setResponseCode(400)
        val touches = chunk.optJSONArray("touches")?.length() ?: 0
        synchronized(shots) {
            shotChunks++
            shotFrames += frames.length()
            shotTouches += touches
            shotBytes += size
            for (i in 0 until frames.length()) {
                val f = frames.optJSONObject(i) ?: continue
                val jpeg = decodeBase64(f.optString("data")) ?: continue
                shots.addLast(Shot(f.optLong("ts"), jpeg))
                while (shots.size > MAX_SHOTS) shots.removeFirst()
            }
        }
        return MockResponse().setResponseCode(200)
    }

    fun screenshotStats(): ScreenshotStats =
        synchronized(shots) { ScreenshotStats(shotChunks, shotFrames, shotTouches, shotBytes) }

    /** Writes the last [MAX_SHOTS] screenshot frames as `shot-<n>-<ts>.jpg` into [dir] (older shot files there are removed). */
    fun exportScreenshots(dir: File): List<File>? = runCatching {
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("shot-") && f.name.endsWith(".jpg") }?.forEach { it.delete() }
        val snapshot = synchronized(shots) { shots.toList() }
        snapshot.mapIndexed { i, s -> File(dir, "shot-%02d-%d.jpg".format(i, s.ts)).also { it.writeBytes(s.jpeg) } }
    }.getOrNull()

    /** `java.util.Base64` needs API 26 and this app supports 24; a frame's `data` is plain base64. */
    private fun decodeBase64(s: String): ByteArray? {
        val clean = s.trimEnd('=')
        if (clean.isEmpty()) return null
        val out = ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
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
        const val FORMAT_SCREEN = "artracker-screen-v1"
        const val MAX_SHOTS = 20
        const val MAX_UNCOMPRESSED = 4 * 1024 * 1024
        const val MAX_CONTROL_BODY = 16 * 1024
        const val MAX_UPLOAD_KEYS = 200
        val SAFE_TOKEN = Regex("^[a-zA-Z0-9_-]{8,128}$")
        val SAFE_HOST = Regex("^[a-zA-Z0-9.\\-]{1,64}:[0-9]{1,5}$")
    }
}
