package id.art.tracker.transport

import id.art.tracker.ARTrackerSender
import id.art.tracker.SendRequest
import id.art.tracker.SendResponse
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CustomSenderAdapterTest {
    private val key = "art_live_" + "a".repeat(43)
    private val body = byteArrayOf(1, 2, 3)

    private class Recorder(private val answer: () -> SendResponse) : ARTrackerSender {
        val requests = mutableListOf<SendRequest>()

        override fun send(request: SendRequest): SendResponse {
            requests += request
            return answer()
        }
    }

    private fun answering(status: Int, retryAfter: String? = null) = Recorder { SendResponse(status, retryAfter) }

    private fun adapter(
        sender: ARTrackerSender,
        endpoint: String = "https://ingest.example.com",
        apiKey: String = key,
    ) = CustomSenderAdapter(sender, endpoint, apiKey)

    private fun result(status: Int, retryAfter: String? = null): SendResult =
        adapter(answering(status, retryAfter)).send(body)

    @Test
    fun status2xx_isAccepted() {
        for (status in listOf(200, 202, 204)) assertEquals("status $status", SendResult.Accepted, result(status))
    }

    @Test
    fun status401And403_areUnauthorized() {
        assertEquals(SendResult.Unauthorized, result(401))
        assertEquals(SendResult.Unauthorized, result(403))
    }

    @Test
    fun status413_isTooLarge() {
        assertEquals(SendResult.TooLarge, result(413))
    }

    @Test
    fun status429And503_honourRetryAfterSeconds() {
        assertEquals(SendResult.RetryAfter(30_000), result(429, "30"))
        assertEquals(SendResult.RetryAfter(7_000), result(503, " 7 "))
    }

    @Test
    fun retryAfter_isCappedAtOneHour_andUnusableValuesAreIgnored() {
        assertEquals(SendResult.RetryAfter(3_600_000), result(429, "7200"))
        assertEquals(SendResult.RetryAfter(null), result(429, null))
        assertEquals(SendResult.RetryAfter(null), result(429, "Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(SendResult.RetryAfter(null), result(429, "-5"))
    }

    @Test
    fun otherClientErrors_areDropped() {
        for (status in listOf(400, 404, 422)) assertEquals("status $status", SendResult.Drop(status), result(status))
    }

    @Test
    fun serverErrorsAndUnexpectedStatuses_areRetried() {
        for (status in listOf(500, 502, 302, 100)) {
            assertEquals("status $status", SendResult.RetryAfter(null), result(status))
        }
    }

    @Test
    fun ioException_isANetworkError_keepingTheCause() {
        val failure = IOException("offline")
        val adapter = adapter(ARTrackerSender { throw failure })

        val r = adapter.send(body)

        assertTrue(r is SendResult.NetworkError)
        assertSame(failure, (r as SendResult.NetworkError).cause)
    }

    @Test
    fun anyOtherException_isANetworkError_notAcrash() {
        val adapter = adapter(ARTrackerSender { throw IllegalStateException("host bug") })

        assertTrue(adapter.send(body) is SendResult.NetworkError)
    }

    @Test
    fun errors_arePropagated() {
        val adapter = adapter(ARTrackerSender { throw OutOfMemoryError("test") })

        try {
            adapter.send(body)
            fail("an Error must not be swallowed")
        } catch (e: OutOfMemoryError) {
            assertEquals("test", e.message)
        }
    }

    @Test
    fun theRequest_carriesTheIngestUrlHeadersAndTheUntouchedBody() {
        val sender = answering(202)

        adapter(sender, endpoint = "https://ingest.example.com/").send(body)

        val request = sender.requests.single()
        assertEquals("https://ingest.example.com/api/v1/ingest", request.url)
        assertEquals(key, request.headers["X-Artracker-Key"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertTrue(request.headers.getValue("User-Agent").startsWith("artracker-android/"))
        assertSame("the gzip body must be handed over as is", body, request.body)
    }

    @Test
    fun anInvalidEndpoint_dropsTheBatch_withoutCallingTheSender() {
        for (endpoint in listOf("not a url", "ftp://ingest.example.com", "")) {
            val sender = answering(200)

            assertEquals("endpoint '$endpoint'", SendResult.Drop(0), adapter(sender, endpoint = endpoint).send(body))
            assertTrue("endpoint '$endpoint'", sender.requests.isEmpty())
        }
    }

    @Test
    fun aKeyWithControlCharacters_dropsTheBatch_withoutCallingTheSender() {
        val sender = answering(200)

        val r = adapter(sender, apiKey = "art_live_abc\r\nX-Injected: 1").send(body)

        assertEquals(SendResult.Drop(0), r)
        assertTrue(sender.requests.isEmpty())
    }
}
