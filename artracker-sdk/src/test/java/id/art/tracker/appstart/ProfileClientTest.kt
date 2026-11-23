package id.art.tracker.appstart

import id.art.tracker.transport.IngestClient
import id.art.tracker.transport.IngestEndpoint
import id.art.tracker.transport.SendResult
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProfileClientTest {
    private lateinit var server: MockWebServer
    private val body = byteArrayOf(0x1f, 0x8b.toByte(), 9, 8, 7)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun postsGzipToTheProfilesPath_withIngestHeaders() {
        server.enqueue(MockResponse().setResponseCode(202))
        val client = IngestClient(server.url("/").toString().trimEnd('/'), "art_live_testkey", path = IngestEndpoint.PROFILES_PATH)

        assertEquals(SendResult.Accepted, client.send(body))

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/api/v1/app-start/profiles", req.path)
        assertEquals("art_live_testkey", req.getHeader("X-Artracker-Key"))
        assertEquals("gzip", req.getHeader("Content-Encoding"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(req.getHeader("User-Agent")!!.startsWith("artracker-android/"))
        assertArrayEquals(body, req.body.readByteArray())
    }

    @Test
    fun theDefaultPathIsStillIngest() {
        server.enqueue(MockResponse().setResponseCode(202))
        IngestClient(server.url("/").toString().trimEnd('/'), "art_live_testkey").send(body)

        assertEquals("/api/v1/ingest", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun urlHonoursThePathAndABasePath() {
        assertEquals("https://x.io/api/v1/app-start/profiles", IngestEndpoint.url("https://x.io/", IngestEndpoint.PROFILES_PATH)!!.toString())
        assertEquals("https://x.io/api/v1/ingest", IngestEndpoint.url("https://x.io")!!.toString())
        assertEquals("https://x.io/base/api/v1/app-start/profiles", IngestEndpoint.url("https://x.io/base", IngestEndpoint.PROFILES_PATH)!!.toString())
    }
}
