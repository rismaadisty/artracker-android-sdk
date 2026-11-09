package id.art.tracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.SdkLog
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `NetworkConfig.sender` end to end: the facade delivers through the host's sender, not through HttpURLConnection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomSenderFacadeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = "art_live_" + "a".repeat(43)
    private val requests = CopyOnWriteArrayList<SendRequest>()

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        context.deleteDatabase("artracker.db")
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        context.deleteDatabase("artracker.db")
        SdkLog.enabled = false
    }

    private fun start(sender: ARTrackerSender) {
        ARTracker.init(
            context,
            ARTrackerConfig(
                endpoint = "https://ingest.example.com",
                apiKey = key,
                environment = "test",
                appVersion = "1.2.3",
                network = NetworkConfig(sender = sender),
            ),
        )
    }

    private fun awaitIdle() {
        runBlocking { withTimeout(15_000) { ARTrackerCore.instanceOrNull()!!.awaitIdle() } }
    }

    private fun envelope(request: SendRequest): JSONObject =
        JSONObject(GZIPInputStream(request.body.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) })

    private fun itemNames(request: SendRequest): List<String> {
        val items = envelope(request).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("name") }
    }

    @Test
    fun aFlush_goesThroughTheHostSender() {
        start(ARTrackerSender { request -> requests.add(request); SendResponse(202) })

        ARTracker.track("checkout_started")
        ARTracker.flush()
        awaitIdle()

        val request = requests.single()
        assertEquals("https://ingest.example.com/api/v1/ingest", request.url)
        assertEquals(key, request.headers["X-Artracker-Key"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertTrue("checkout_started" in itemNames(request))
        assertEquals("test", envelope(request).getJSONObject("context").getString("environment"))
        val diagnostics = ARTracker.diagnostics()
        assertEquals("accepted", diagnostics.lastSendResult)
        assertEquals(0, diagnostics.pendingItems)
    }

    @Test
    fun anIOException_keepsTheItemsQueued_forARetry() {
        start(ARTrackerSender { throw IOException("offline") })

        ARTracker.track("offline_event")
        ARTracker.flush()
        awaitIdle()

        val diagnostics = ARTracker.diagnostics()
        assertEquals("network_error", diagnostics.lastSendResult)
        assertTrue("the batch must stay in the queue", diagnostics.pendingItems > 0)
        assertFalse(diagnostics.disabled)
    }

    @Test
    fun a401FromTheHostSender_tripsTheKillSwitch() {
        start(ARTrackerSender { SendResponse(401) })

        ARTracker.track("rejected")
        ARTracker.flush()
        awaitIdle()

        val diagnostics = ARTracker.diagnostics()
        assertEquals("unauthorized", diagnostics.lastSendResult)
        assertTrue(diagnostics.disabled)
    }

    @Test
    fun aSenderThatThrowsARuntimeException_neverReachesTheHost() {
        start(ARTrackerSender { throw IllegalStateException("bug in the host sender") })

        ARTracker.track("still_fine")
        ARTracker.flush()
        awaitIdle()

        assertEquals("network_error", ARTracker.diagnostics().lastSendResult)
    }
}
