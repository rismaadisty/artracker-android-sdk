package id.art.tracker.playground.local

import id.art.tracker.playground.inspector.EventRing
import id.art.tracker.playground.inspector.ItemSource
import id.art.tracker.playground.inspector.envelope
import id.art.tracker.playground.inspector.itemJson
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InAppSinkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ring = EventRing()
    private val sink = InAppSink(requestedPort = 0, ring = ring)

    @After
    fun stop() = sink.shutdown()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** The same call the SDK's IngestClient makes: HttpURLConnection, fixed-length gzip body, key header. */
    private fun post(
        body: ByteArray,
        path: String = "/api/v1/ingest",
        method: String = "POST",
        key: String = "art_live_" + "k".repeat(43),
    ): Pair<Int, String> {
        val conn = URL("http://localhost:${sink.port}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.doOutput = method == "POST"
        conn.setRequestProperty("X-Artracker-Key", key)
        conn.setRequestProperty("Content-Encoding", "gzip")
        if (method == "POST") {
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        return code to text
    }

    /** One request of the replay upload, the way the SDK's ReplayClient makes it (no Content-Encoding, plain or gzip body). */
    private fun call(method: String, url: String, body: ByteArray, contentType: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        conn.setRequestProperty("X-Artracker-Key", "art_live_" + "k".repeat(43))
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        return code to text
    }

    private fun base() = "http://localhost:${sink.port}"

    private fun requestUploadUrl(seq: Int): String {
        val body = "{\"session_id\":\"session-abc-123\",\"chunk_seq\":$seq,\"size_bytes\":10,\"environment\":\"test\"}"
        val (code, text) = call("POST", base() + "/api/v1/replay/chunk-url", body.toByteArray(), "application/json")
        assertEquals(200, code)
        return JSONObject(text).getString("upload_url")
    }

    @Test
    fun replayUpload_threeSteps_storesChunkAndExports() {
        assertTrue(sink.start())
        val uploadUrl = requestUploadUrl(seq = 7)
        val events = "[{\"type\":4,\"timestamp\":1000},{\"type\":2,\"timestamp\":1001},{\"type\":3,\"timestamp\":2500}]"

        assertEquals(200, call("PUT", uploadUrl, gzip(events.toByteArray()), "application/gzip").first)
        val (ackCode, ackText) = call(
            "POST", base() + "/api/v1/replay/chunk-ack",
            "{\"session_id\":\"session-abc-123\",\"chunk_seq\":7}".toByteArray(), "application/json",
        )
        assertEquals(200, ackCode)
        assertEquals("{\"status\":\"acknowledged\"}", ackText)

        val file = sink.exportReplayChunks(tmp.newFolder("replay"))
        val array = JSONArray(file!!.readText())
        assertEquals(1, array.length())
        val chunk = array.getJSONObject(0)
        assertEquals(7, chunk.getInt("seq"))
        assertEquals(1000L, chunk.getLong("start"))
        assertEquals(2500L, chunk.getLong("end"))
        assertTrue(chunk.getBoolean("full_snapshot"))
        assertEquals("android", chunk.getString("platform"))
        val stored = chunk.getJSONArray("events")
        assertEquals(listOf(4, 2, 3), (0 until stored.length()).map { stored.getJSONObject(it).getInt("type") })
        // The replay upload is not an ingest item: the Inspector does not list it.
        assertEquals(0, ring.items.value.size)
    }

    @Test
    fun replayUpload_neverStoresTheApiKey() {
        assertTrue(sink.start())
        requestUploadUrl(seq = 0)
        val file = sink.exportReplayChunks(tmp.newFolder("nokey"))
        assertFalse(file?.readText().orEmpty().contains("art_live_"))
    }

    @Test
    fun replayUpload_oversizedBody_isRefused413() {
        assertTrue(sink.start())
        val uploadUrl = requestUploadUrl(seq = 1)

        assertEquals(413, call("PUT", uploadUrl, gzip(ByteArray(5 * 1024 * 1024)), "application/gzip").first)
        assertEquals("[]", sink.exportReplayChunks(tmp.newFolder("big"))!!.readText())
    }

    @Test
    fun replayUpload_corruptGzip_is400() {
        assertTrue(sink.start())
        val uploadUrl = requestUploadUrl(seq = 1)

        assertEquals(400, call("PUT", uploadUrl, "not gzip".toByteArray(), "application/gzip").first)
        assertEquals(400, call("PUT", uploadUrl, gzip("{\"not\":\"an array\"}".toByteArray()), "application/gzip").first)
    }

    @Test
    fun unknownPath_is404() {
        assertTrue(sink.start())
        assertEquals(404, call("POST", base() + "/api/v1/replay/nope", "{}".toByteArray(), "application/json").first)
        assertEquals(404, call("PUT", base() + "/api/v1/replay/chunk-url", "{}".toByteArray(), "application/json").first)
    }

    @Test
    fun acceptsAGzipEnvelopeAndShowsItsItems() {
        assertTrue(sink.start())
        val (code, text) = post(gzip(envelope(itemJson("a"), itemJson("b")).toByteArray()))
        assertEquals(202, code)
        assertEquals("{\"status\":\"accepted\",\"queued\":2}", text)
        assertEquals(2, ring.items.value.size)
        assertTrue(ring.items.value.all { it.source == ItemSource.SENT_PAYLOAD })
    }

    @Test
    fun bindsToLoopbackOnly() {
        assertTrue(sink.start())
        val address = sink.boundAddress
        assertNotNull(address)
        assertTrue("sink must not listen on a non-loopback address: $address", address!!.isLoopbackAddress)
    }

    @Test
    fun startReturnsFalseWhenThePortIsBusy() {
        ServerSocket(0, 1, InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))).use { held ->
            val busy = InAppSink(requestedPort = held.localPort, ring = ring)
            assertFalse(busy.start())
            busy.shutdown()
        }
    }

    @Test
    fun startIsIdempotent() {
        assertTrue(sink.start())
        val port = sink.port
        assertTrue(sink.start())
        assertEquals(port, sink.port)
    }

    @Test
    fun wrongMethodOrPathIs404() {
        assertTrue(sink.start())
        assertEquals(404, post(ByteArray(0), method = "GET").first)
        assertEquals(404, post(gzip("{}".toByteArray()), path = "/elsewhere").first)
        assertEquals(0, ring.items.value.size)
    }

    @Test
    fun corruptGzipIs400AndDoesNotThrow() {
        assertTrue(sink.start())
        assertEquals(400, post("this is not gzip".toByteArray()).first)
        assertEquals(0, ring.items.value.size)
    }

    @Test
    fun bodyOverTheCapIs413AndIsNotParsed() {
        assertTrue(sink.start())
        assertEquals(413, post(gzip(ByteArray(5 * 1024 * 1024))).first)
        assertEquals(0, ring.items.value.size)
    }

    @Test
    fun neverStoresTheApiKey() {
        assertTrue(sink.start())
        val key = "art_live_SECRETSECRETSECRETSECRETSECRETSECRET"
        post(gzip(envelope(itemJson("a")).toByteArray()), key = key)
        assertFalse(ring.items.value.toString().contains("SECRETSECRET"))
        val file = sink.exportReplayChunks(tmp.newFolder("out"))
        assertFalse(file?.readText().orEmpty().contains("SECRETSECRET"))
    }

    @Test
    fun replayChunksAreExportedIntactAndCappedAtFifty() {
        assertTrue(sink.start())
        repeat(60) { i ->
            val chunk = "{\"seq\":$i,\"events\":[{\"type\":4},{\"type\":2},{\"type\":3}]}"
            post(gzip(envelope(itemJson("replay_chunk", type = "session", attributes = chunk)).toByteArray()))
        }
        val file = sink.exportReplayChunks(tmp.newFolder("chunks"))
        assertNotNull(file)
        val array = JSONArray(file!!.readText())
        assertEquals(50, array.length())
        assertEquals(10, array.getJSONObject(0).getInt("seq"))
        assertEquals(59, array.getJSONObject(49).getInt("seq"))
        assertEquals(3, array.getJSONObject(0).getJSONArray("events").length())
        assertTrue(ring.items.value.all { it.attributes["events"] == "3 events" })
    }

    private fun screenChunk(frames: Int, seq: Int = 0): ByteArray {
        val f = (0 until frames).joinToString(",") {
            "{\"ts\":${1000 + it},\"screen\":\"/home\",\"w\":10,\"h\":20,\"vw\":10,\"vh\":20,\"mime\":\"image/jpeg\"," +
                "\"data\":\"${java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, it.toByte()))}\",\"masked\":1}"
        }
        return gzip(
            ("{\"format\":\"artracker-screen-v1\",\"platform\":\"android\",\"seq\":$seq,\"frames\":[$f]," +
                "\"touches\":[{\"ts\":1000,\"x\":1,\"y\":2,\"kind\":\"down\"}]}").toByteArray(),
        )
    }

    private fun requestScreenUploadUrl(seq: Int): Pair<Int, String> {
        val body = "{\"session_id\":\"session-abc-123\",\"chunk_seq\":$seq,\"size_bytes\":10,\"environment\":\"test\"," +
            "\"format\":\"artracker-screen-v1\"}"
        return call("POST", base() + "/api/v1/replay/chunk-url", body.toByteArray(), "application/json")
    }

    @Test
    fun replayConfig_tellsTheSdkWhetherScreenshotsAreAllowed() {
        assertTrue(sink.start())
        val (code, text) = post(ByteArray(0), path = "/api/v1/replay/config", method = "GET")
        assertEquals(200, code)
        assertTrue(JSONObject(text).getBoolean("screenshot_enabled"))
        assertTrue(JSONObject(text).getDouble("max_fps") > 0)
        sink.screenshotEnabled = false
        assertFalse(JSONObject(post(ByteArray(0), path = "/api/v1/replay/config", method = "GET").second).getBoolean("screenshot_enabled"))
    }

    @Test
    fun whenScreenshotsAreOff_aScreenshotChunkUrlGets403ScreenshotDisabled_butWireframesStillWork() {
        assertTrue(sink.start())
        sink.screenshotEnabled = false
        val (code, text) = requestScreenUploadUrl(0)
        assertEquals(403, code)
        assertEquals("screenshot_disabled", JSONObject(text).getString("error"))
        requestUploadUrl(seq = 1) // a wireframe chunk is unaffected
    }

    @Test
    fun screenshotChunk_isAccepted_counted_andItsFramesCanBeExportedAsJpegs() {
        assertTrue(sink.start())
        val (code, text) = requestScreenUploadUrl(3)
        assertEquals(200, code)
        val uploadUrl = JSONObject(text).getString("upload_url")
        assertEquals(200, call("PUT", uploadUrl, screenChunk(frames = 2, seq = 3), "application/gzip").first)

        val stats = sink.screenshotStats()
        assertEquals(1, stats.chunks)
        assertEquals(2, stats.frames)
        assertEquals(1, stats.touches)
        val dir = tmp.newFolder("shots")
        val files = sink.exportScreenshots(dir)!!
        assertEquals(2, files.size)
        assertTrue(files.all { it.name.endsWith(".jpg") && it.length() == 4L })
        // a screenshot chunk is not a wireframe chunk
        assertEquals("[]", sink.exportReplayChunks(tmp.newFolder("wf"))!!.readText())
    }

    @Test
    fun screenshotChunk_neverStoresTheApiKey_andOnlyTheLastFramesAreKept() {
        assertTrue(sink.start())
        repeat(30) { seq ->
            val uploadUrl = JSONObject(requestScreenUploadUrl(seq).second).getString("upload_url")
            call("PUT", uploadUrl, screenChunk(frames = 1, seq = seq), "application/gzip")
        }
        val files = sink.exportScreenshots(tmp.newFolder("cap"))!!
        assertTrue(files.size <= 20)
        assertTrue(files.none { it.name.contains("art_live_") })
    }
}
