package id.art.tracker.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayResponseTest {
    private fun map(step: ReplayStep, status: Int, retryAfter: String? = null) =
        ReplayResponse.map(step, status, retryAfter)

    @Test
    fun chunkUrl_table() {
        assertEquals(ReplayResult.Unauthorized, map(ReplayStep.CHUNK_URL, 401))
        assertEquals(ReplayResult.Unauthorized, map(ReplayStep.CHUNK_URL, 403))
        assertTrue(map(ReplayStep.CHUNK_URL, 413) is ReplayResult.Drop)
        assertTrue(map(ReplayStep.CHUNK_URL, 400) is ReplayResult.Drop)
        assertEquals(ReplayResult.Retry(7_000), map(ReplayStep.CHUNK_URL, 503, "7"))
        assertEquals(ReplayResult.Retry(null), map(ReplayStep.CHUNK_URL, 429, null))
        assertEquals(ReplayResult.Retry(null), map(ReplayStep.CHUNK_URL, 500))
        // Redirects are never followed: a 3xx is a misconfiguration worth retrying, not a success.
        assertEquals(ReplayResult.Retry(null), map(ReplayStep.CHUNK_URL, 302))
    }

    @Test
    fun upload_table() {
        // A 401/403 on the PUT is an expired signature, not a bad API key: start over, never the kill-switch.
        assertEquals(ReplayResult.Retry(0), map(ReplayStep.UPLOAD, 401))
        assertEquals(ReplayResult.Retry(0), map(ReplayStep.UPLOAD, 403))
        assertTrue(map(ReplayStep.UPLOAD, 404) is ReplayResult.Drop)
        assertEquals(ReplayResult.Retry(null), map(ReplayStep.UPLOAD, 503))
        assertEquals(ReplayResult.Retry(null), map(ReplayStep.UPLOAD, 500))
    }

    @Test
    fun ack_table() {
        assertEquals(ReplayResult.Unauthorized, map(ReplayStep.ACK, 403))
        assertEquals(ReplayResult.Unauthorized, map(ReplayStep.ACK, 401))
        assertTrue(map(ReplayStep.ACK, 400) is ReplayResult.Drop)
        assertEquals(ReplayResult.Retry(3_000), map(ReplayStep.ACK, 429, "3"))
        assertEquals(ReplayResult.Retry(null), map(ReplayStep.ACK, 502))
    }

    @Test
    fun anySuccessStatus_continues() {
        for (step in ReplayStep.values()) {
            for (status in listOf(200, 201, 202, 204, 299)) assertNull("$step $status", map(step, status))
        }
    }
}
