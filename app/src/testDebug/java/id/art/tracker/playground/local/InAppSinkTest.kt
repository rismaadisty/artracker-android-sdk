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
}
