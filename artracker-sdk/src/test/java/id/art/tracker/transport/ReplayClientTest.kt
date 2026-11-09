package id.art.tracker.transport

import id.art.tracker.core.Iso8601
import id.art.tracker.testutil.FakeReplayBackend
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplayClientTest {
    private lateinit var backend: FakeReplayBackend
    private val key = "art_live_testkey"
    private val gzip = ByteArray(300) { (it * 7).toByte() }

    @Before
    fun setUp() {
        backend = FakeReplayBackend()
    }

    @After
    fun tearDown() {
        backend.shutdown()
    }

    private fun upload(
        userId: String? = "user-1",
        hasError: Boolean = true,
    ) = ReplayUpload(
        id = "row-1",
        sessionId = "session_abc-123",
        seq = 4,
        gzipBody = gzip,
        eventCount = 17,
        startMs = 1_700_000_000_000L,
        durationMs = 8_500L,
        hasError = hasError,
        userId = userId,
        environment = "staging",
    )

    private fun client(endpoint: String = backend.endpoint, apiKey: String = key) = ReplayClient(endpoint, apiKey)

    private fun keys(o: org.json.JSONObject): Set<String> = o.keys().asSequence().toSet()

    @Test
    fun upload_runsThreeStepsInOrder() {
        val result = client().upload(upload())

        assertEquals(ReplayResult.Done, result)
        assertEquals(
            listOf("POST" to FakeReplayBackend.CHUNK_URL, "PUT" to FakeReplayBackend.UPLOAD, "POST" to FakeReplayBackend.ACK),
            backend.requests.map { it.method to it.route },
        )
    }

    @Test
    fun chunkUrlBody_hasExactFields() {
        client().upload(upload())

        val body = backend.requestsTo(FakeReplayBackend.CHUNK_URL).single().json()
        assertEquals(
            setOf("session_id", "chunk_seq", "size_bytes", "user_id", "environment", "start_time"),
            keys(body),
        )
        assertEquals("session_abc-123", body.getString("session_id"))
        assertEquals(4, body.getInt("chunk_seq"))
        assertEquals(gzip.size, body.getInt("size_bytes"))
        assertEquals("user-1", body.getString("user_id"))
        assertEquals("staging", body.getString("environment"))
        assertEquals(Iso8601.format(1_700_000_000_000L), body.getString("start_time"))
        assertFalse(body.has("direct"))
    }

    @Test
    fun chunkUrlBody_omitsUserIdWhenNull() {
        client().upload(upload(userId = null))

        val body = backend.requestsTo(FakeReplayBackend.CHUNK_URL).single().json()
        assertFalse(body.has("user_id"))
    }

    @Test
    fun putBody_isTheGzipBytes_withGzipContentType_andNoApiKey() {
        client().upload(upload())

        val put = backend.requestsTo(FakeReplayBackend.UPLOAD).single()
        assertArrayEquals(gzip, put.body)
        assertEquals("application/gzip", put.header("Content-Type"))
        assertNull(put.header("X-Artracker-Key"))
        assertEquals(gzip.size.toString(), put.header("Content-Length"))
    }

    @Test
    fun ackBody_hasExactFields() {
        client().upload(upload())

        val body = backend.requestsTo(FakeReplayBackend.ACK).single().json()
        assertEquals(
            setOf("session_id", "chunk_seq", "size_bytes", "duration_ms", "event_count", "has_error", "has_rage_click", "has_dead_click"),
            keys(body),
        )
        assertEquals("session_abc-123", body.getString("session_id"))
        assertEquals(4, body.getInt("chunk_seq"))
        assertEquals(gzip.size, body.getInt("size_bytes"))
        assertEquals(8_500L, body.getLong("duration_ms"))
        assertEquals(17, body.getInt("event_count"))
        assertTrue(body.getBoolean("has_error"))
        assertFalse(body.getBoolean("has_rage_click"))
        assertFalse(body.getBoolean("has_dead_click"))
    }

    @Test
    fun posts_carryApiKey_jsonContentType_noContentEncoding() {
        client().upload(upload())

        for (route in listOf(FakeReplayBackend.CHUNK_URL, FakeReplayBackend.ACK)) {
            val post = backend.requestsTo(route).single()
            assertEquals(key, post.header("X-Artracker-Key"))
            assertTrue(post.header("Content-Type")!!.startsWith("application/json"))
            assertNull(post.header("Content-Encoding"))
        }
    }

    @Test
    fun malformedChunkUrlResponse_isRetryNull() {
        for (body in listOf("not json", "{}", """{"upload_url":""}""", """{"upload_url":42}""")) {
            backend.chunkUrl = { MockResponse().setResponseCode(200).setBody(body) }
            assertEquals(body, ReplayResult.Retry(null), client().upload(upload()))
        }
        assertTrue(backend.requestsTo(FakeReplayBackend.UPLOAD).isEmpty())
        assertTrue(backend.requestsTo(FakeReplayBackend.ACK).isEmpty())
    }

    @Test
    fun redirect_isNotFollowed() {
        backend.chunkUrl = { MockResponse().setResponseCode(302).addHeader("Location", "${backend.endpoint}/elsewhere") }

        assertEquals(ReplayResult.Retry(null), client().upload(upload()))
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun uploadUrlDowngradingToCleartext_isRetryNull_andNeverFetched() {
        // An https endpoint cannot be tested against MockWebServer without TLS, so the rule is checked through
        // ReplayEndpointTest; here a bogus scheme proves the client enforces acceptability before the PUT.
        backend.chunkUrl = { MockResponse().setResponseCode(200).setBody("""{"upload_url":"ftp://example.invalid/u"}""") }

        assertEquals(ReplayResult.Retry(null), client().upload(upload()))
        assertTrue(backend.requestsTo(FakeReplayBackend.UPLOAD).isEmpty())
    }

    @Test
    fun stepFailuresMapThroughReplayResponse() {
        backend.upload = { MockResponse().setResponseCode(403) }
        assertEquals(ReplayResult.Retry(0), client().upload(upload()))
        assertTrue(backend.requestsTo(FakeReplayBackend.ACK).isEmpty())

        backend.upload = { MockResponse().setResponseCode(200) }
        backend.ack = { MockResponse().setResponseCode(401) }
        assertEquals(ReplayResult.Unauthorized, client().upload(upload()))

        backend.chunkUrl = { MockResponse().setResponseCode(503).addHeader("Retry-After", "30") }
        assertEquals(ReplayResult.Retry(30_000), client().upload(upload()))
    }

    @Test
    fun ioFailure_isNetworkError() {
        val dead = client()
        backend.shutdown()

        assertTrue(dead.upload(upload()) is ReplayResult.NetworkError)
    }

    @Test
    fun invalidEndpointOrControlCharKey_isDrop() {
        assertTrue(client(endpoint = "not a url").upload(upload()) is ReplayResult.Drop)
        assertTrue(client(endpoint = "ftp://example.invalid").upload(upload()) is ReplayResult.Drop)
        assertTrue(client(apiKey = "key\r\nX-Evil: 1").upload(upload()) is ReplayResult.Drop)
        assertTrue(backend.requests.isEmpty())
    }
}
