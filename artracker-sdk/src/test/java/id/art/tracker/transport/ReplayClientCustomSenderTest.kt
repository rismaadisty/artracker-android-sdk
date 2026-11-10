package id.art.tracker.transport

import id.art.tracker.ARTrackerSender
import id.art.tracker.SendRequest
import id.art.tracker.SendResponse
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The replay upload through a host-provided [ARTrackerSender] (certificate pinning, proxy, Chucker). */
class ReplayClientCustomSenderTest {
    private val key = "art_live_" + "k".repeat(43)
    private val gzip = ByteArray(64) { it.toByte() }
    private val upload = ReplayUpload("row", "session_abc-123", 2, gzip, 5, 1_000, 500, false, "u1", "staging")
    private val uploadUrl = "https://storage.example.com/replay/u?sig=s"

    private class Scripted(private val answer: (SendRequest) -> SendResponse) : ARTrackerSender {
        val requests = mutableListOf<SendRequest>()

        override fun send(request: SendRequest): SendResponse {
            requests += request
            return answer(request)
        }
    }

    private fun happy(request: SendRequest): SendResponse = when (request.method) {
        "PUT" -> SendResponse(200)
        else -> if (request.url.endsWith("/chunk-url")) {
            SendResponse(200, null, "{\"upload_url\":\"$uploadUrl\"}")
        } else {
            SendResponse(200)
        }
    }

    private fun client(sender: ARTrackerSender, endpoint: String = "https://api.example.com") =
        ReplayClient(endpoint, key, sender = sender)

    @Test
    fun threeSteps_goThroughTheSender_withTheRightMethodsUrlsAndBodies() {
        val sender = Scripted(::happy)

        assertEquals(ReplayResult.Done, client(sender).upload(upload))

        assertEquals(listOf("POST", "PUT", "POST"), sender.requests.map { it.method })
        assertEquals(
            listOf("https://api.example.com/api/v1/replay/chunk-url", uploadUrl, "https://api.example.com/api/v1/replay/chunk-ack"),
            sender.requests.map { it.url },
        )
        val (chunkUrl, put, ack) = sender.requests
        assertEquals("session_abc-123", JSONObject(chunkUrl.body.toString(Charsets.UTF_8)).getString("session_id"))
        assertEquals(64, JSONObject(chunkUrl.body.toString(Charsets.UTF_8)).getInt("size_bytes"))
        assertArrayEquals(gzip, put.body)
        val ackJson = JSONObject(ack.body.toString(Charsets.UTF_8))
        assertEquals(2, ackJson.getInt("chunk_seq"))
        assertEquals(5, ackJson.getInt("event_count"))
    }

    @Test
    fun apiKey_isOnlyOnThePosts_andTheContentTypesAreRight() {
        val sender = Scripted(::happy)
        client(sender).upload(upload)

        val (chunkUrl, put, ack) = sender.requests
        assertEquals(key, chunkUrl.headers["X-Artracker-Key"])
        assertEquals(key, ack.headers["X-Artracker-Key"])
        assertNull(put.headers["X-Artracker-Key"])
        assertEquals("application/json", chunkUrl.headers["Content-Type"])
        assertEquals("application/gzip", put.headers["Content-Type"])
        assertNull(chunkUrl.headers["Content-Encoding"])
        assertTrue(put.headers["User-Agent"]!!.startsWith("artracker-android/"))
    }

    @Test
    fun senderWithoutResponseBody_cannotCompleteChunkUrl_andNeverPuts() {
        val sender = Scripted { SendResponse(200) }

        assertEquals(ReplayResult.Retry(null), client(sender).upload(upload))
        assertEquals(1, sender.requests.size)
    }

    @Test
    fun statusesMapThroughReplayResponse() {
        assertEquals(
            ReplayResult.Retry(0),
            client(Scripted { r -> if (r.method == "PUT") SendResponse(403) else happy(r) }).upload(upload),
        )
        assertEquals(
            ReplayResult.Unauthorized,
            client(Scripted { r -> if (r.url.endsWith("/chunk-ack")) SendResponse(401) else happy(r) }).upload(upload),
        )
        assertEquals(
            ReplayResult.Retry(30_000),
            client(Scripted { SendResponse(503, "30") }).upload(upload),
        )
    }

    @Test
    fun senderFailures_areNetworkErrors() {
        assertTrue(client(Scripted { throw IOException("offline") }).upload(upload) is ReplayResult.NetworkError)
        assertTrue(client(Scripted { throw IllegalStateException("boom") }).upload(upload) is ReplayResult.NetworkError)
    }

    @Test
    fun downgradeToCleartext_isRefusedBeforeTheSenderIsAskedToPut() {
        val sender = Scripted { r ->
            if (r.url.endsWith("/chunk-url")) SendResponse(200, null, "{\"upload_url\":\"http://storage.example.com/u\"}") else SendResponse(200)
        }

        assertEquals(ReplayResult.Retry(null), client(sender).upload(upload))
        assertEquals(1, sender.requests.size)
    }

    @Test
    fun invalidEndpointOrControlCharKey_isDrop_withoutCallingTheSender() {
        val sender = Scripted(::happy)

        assertTrue(client(sender, endpoint = "not a url").upload(upload) is ReplayResult.Drop)
        assertTrue(ReplayClient("https://api.example.com", "bad\r\nkey", sender = sender).upload(upload) is ReplayResult.Drop)
        assertTrue(sender.requests.isEmpty())
    }

    @Test
    fun screenshotDisabled403_throughTheSender() {
        val sender = Scripted { SendResponse(403, null, """{"error":"screenshot_disabled"}""") }
        val screen = ReplayUpload("row", "s", 0, gzip, 1, 1_000, 0, false, null, "staging", ReplayFormat.SCREEN_V1)
        assertEquals(ReplayResult.ScreenshotDisabled, client(sender).upload(screen))
    }

    @Test
    fun screenshot403_withTheBodyDroppedBySender_isStillScreenshotDisabled() {
        val sender = Scripted { SendResponse(403, null, null) }
        val screen = ReplayUpload("row", "s", 0, gzip, 1, 1_000, 0, false, null, "staging", ReplayFormat.SCREEN_V1)
        assertEquals(ReplayResult.ScreenshotDisabled, client(sender).upload(screen))
    }
}
