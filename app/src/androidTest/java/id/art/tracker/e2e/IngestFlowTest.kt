package id.art.tracker.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import id.art.tracker.ARTracker
import id.art.tracker.MockIngest
import id.art.tracker.TestPlaygroundApplication
import id.art.tracker.playground.settings.EndpointGuard
import id.art.tracker.sample.SampleHttp
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IngestFlowTest {
    private val ingest get() = TestPlaygroundApplication.ingest

    @Before
    fun clean() = ingest.clear()

    @After
    fun optBackIn() = ARTracker.optOut(false)

    private fun containing(text: String): (List<MockIngest.Recorded>) -> Boolean = { rs -> rs.any { it.body.contains(text) } }

    @Test
    fun trackReachesIngestAsGzipBatch() {
        ARTracker.track("e2e_event", mapOf("k" to "v"))
        ARTracker.flush()
        val request = ingest.awaitUntil(10_000, containing("e2e_event")).first { it.body.contains("e2e_event") }
        assertEquals("/api/v1/ingest", request.path)
        assertEquals(EndpointGuard.DUMMY_KEY, request.headers["X-Artracker-Key"])
        assertEquals("gzip", request.headers["Content-Encoding"])
    }

    @Test
    fun lastSendResultBecomesAccepted() {
        ARTracker.track("e2e_accept_probe")
        ARTracker.flush()
        val deadline = System.nanoTime() + 10_000_000_000L
        var last: String? = null
        while (System.nanoTime() < deadline) {
            last = ARTracker.diagnostics().lastSendResult // reads the database: this test thread is not the main thread
            if (last == "accepted") break
            Thread.sleep(100)
        }
        assertEquals("accepted", last)
    }

    @Test
    fun optOutDropsQueueAndSendsNothing() {
        ARTracker.optOut(true)
        repeat(5) { ARTracker.track("opted_out_event_$it") }
        ARTracker.flush()
        val seen = ingest.awaitUntil(3_000, containing("opted_out_event_"))
        assertFalse("nothing may be sent while opted out", seen.any { it.body.contains("opted_out_event_") })
        assertEquals(0, ARTracker.diagnostics().pendingItems)
    }

    @Test
    fun retryAfterIsHonouredThenAccepted() {
        ingest.enqueue(503, mapOf("Retry-After" to "1"))
        ARTracker.track("retry_event")
        ARTracker.flush()
        val seen = ingest.awaitUntil(30_000) { rs -> rs.count { it.body.contains("retry_event") } >= 2 }
        assertTrue("the 503'd batch must be sent again", seen.count { it.body.contains("retry_event") } >= 2)
        val deadline = System.nanoTime() + 30_000_000_000L
        while (ARTracker.diagnostics().lastSendResult != "accepted" && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals("accepted", ARTracker.diagnostics().lastSendResult)
    }

    @Test
    fun redactionHoldsOnTheWire() {
        val raw = listOf(
            "hunter2-secret", "qa@example.invalid", "4111111111111111",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJGQUtFIn0.FAKEsignature", "Bearer FAKE.token.value", "fake.user@example.invalid",
        )
        ARTracker.track(
            "redaction_probe",
            mapOf(
                "password" to raw[0], "email" to raw[1], "card" to raw[2], "jwt" to raw[3], "auth" to raw[4],
            ),
        )
        ARTracker.track(raw[5]) // an event NAME that is an email
        ARTracker.flush()
        val seen = ingest.awaitUntil(10_000) { rs -> rs.items().any { it.optString("name") == "redaction_probe" } }
        assertTrue("the probe event never arrived", seen.any { it.body.contains("redaction_probe") })
        for (r in raw) assertFalse("raw value leaked on the wire: $r", seen.any { it.body.contains(r) })
        assertTrue("masked card form expected", seen.any { it.body.contains("**** **** **** 1111") })
    }

    @Test
    fun callsToTheIngestEndpointAreNotRecordedButOtherLocalCallsAre() {
        SampleHttp.get(ingest.url + "/anything") // same host and port as the SDK endpoint: must be skipped
        SampleHttp.get(TestPlaygroundApplication.targetsUrl + "/status/404") // different port: must be recorded
        ARTracker.flush()
        val seen = ingest.awaitUntil(10_000) { rs ->
            rs.items().any { it.optString("name") == "http_request" && it.optJSONObject("attributes")?.optString("url").orEmpty().contains("/status/404") }
        }
        val http = seen.items().filter { it.optString("name") == "http_request" }
        assertTrue("the 404 call must be recorded", http.isNotEmpty())
        val notFound = http.first { it.optJSONObject("attributes")?.optString("url").orEmpty().contains("/status/404") }
        assertEquals("warn", notFound.optString("level"))
        assertEquals(404, (notFound.optJSONObject("attributes") as JSONObject).optInt("status"))
        assertTrue(
            "the SDK's own endpoint must not be recorded",
            http.none { it.optJSONObject("attributes")?.optString("url").orEmpty().contains(":${ingest.port}") },
        )
        assertNotNull(notFound.optJSONObject("attributes")?.optString("host"))
    }
}
