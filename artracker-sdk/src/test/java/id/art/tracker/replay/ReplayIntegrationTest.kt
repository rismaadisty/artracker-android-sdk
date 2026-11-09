package id.art.tracker.replay

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.ARTrackerSender
import id.art.tracker.NetworkConfig
import id.art.tracker.telemetry.okhttp.ARTrackerOkHttpSender
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import id.art.tracker.ReplayConfig
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.core.Clock
import id.art.tracker.testutil.FakeReplayBackend
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.testutil.RecordedCall
import id.art.tracker.transport.ReplayStore
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** End to end: `ARTracker.init` with replay enabled -> activity -> batch closes -> the backend gets the 3-step upload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayIntegrationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var backend: FakeReplayBackend
    private lateinit var textView: TextView

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ClassAvailability.resetOverrides()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
        backend = FakeReplayBackend()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        backend.shutdown()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun awaitAll() {
        val core = ARTrackerCore.instanceOrNull()!!
        runBlocking {
            withTimeout(15_000) {
                core.replayController?.awaitIdle()
                core.awaitIdle()
            }
        }
    }

    private fun startRecording(sender: ARTrackerSender? = null) {
        ARTracker.init(
            app,
            ARTrackerConfig(
                endpoint = backend.endpoint,
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
                replay = ReplayConfig(enabled = true, sampleRate = 1.0),
                network = NetworkConfig(sender = sender),
            ),
        )
        ProcessLifecycleTestUtil.start()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        textView = TextView(activity).apply { text = "hello replay" }
        activity.setContentView(textView)
        idle(1_000)
        awaitAll()
    }

    /** Closes the open chunk and batch now (the real batch window is 10 s of wall-clock time). */
    private fun closeBatch() {
        ARTrackerCore.instanceOrNull()!!.replayController!!.flushChunks()
        awaitAll()
    }

    private fun awaitCalls(route: String, count: Int): List<RecordedCall> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (backend.requestsTo(route).size < count && System.nanoTime() < deadline) Thread.sleep(20)
        return backend.requestsTo(route)
    }

    private fun gunzip(body: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(body)).use { String(it.readBytes(), Charsets.UTF_8) }

    @Test
    fun replayUploads_threeStepsWithValidPayload() {
        startRecording()
        closeBatch()

        val acks = awaitCalls(FakeReplayBackend.ACK, 1)
        assertEquals(1, acks.size)
        val chunkUrl = backend.requestsTo(FakeReplayBackend.CHUNK_URL).single().json()
        val put = backend.requestsTo(FakeReplayBackend.UPLOAD).single()
        val ack = acks.single().json()

        val sessionId = chunkUrl.getString("session_id")
        assertTrue(sessionId, Regex("^[a-zA-Z0-9_-]{8,128}$").matches(sessionId))
        assertEquals(sessionId, ack.getString("session_id"))
        assertEquals(0, chunkUrl.getInt("chunk_seq"))

        val text = gunzip(put.body)
        val events = JSONArray(text)
        assertEquals(4, events.getJSONObject(0).getInt("type"))
        assertEquals(2, events.getJSONObject(1).getInt("type"))
        assertTrue(text.contains("hello replay"))
        assertFalse(text.contains("[max_depth]"))

        assertEquals(put.body.size, chunkUrl.getInt("size_bytes"))
        assertEquals(put.body.size, ack.getInt("size_bytes"))
        assertEquals(events.length(), ack.getInt("event_count"))
    }

    @Test
    fun customSender_carriesTheReplayUploadToo() {
        val delegate = ARTrackerOkHttpSender(OkHttpClient.Builder().followRedirects(false).build())
        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        val sender = ARTrackerSender { request ->
            seen += request.method to request.url.substringBefore('?').removePrefix(backend.endpoint)
            delegate.send(request)
        }
        startRecording(sender)
        closeBatch()
        awaitCalls(FakeReplayBackend.ACK, 1)

        // The three steps went through the host's sender (so Chucker, pinning or a proxy see them)...
        val replaySteps = seen.filter { it.second.startsWith("/api/v1/replay/") }
        assertEquals(
            listOf("POST" to FakeReplayBackend.CHUNK_URL, "PUT" to FakeReplayBackend.UPLOAD, "POST" to FakeReplayBackend.ACK),
            replaySteps,
        )
        // ...and still arrived intact, with the key only on the two POSTs.
        assertNull(backend.requestsTo(FakeReplayBackend.UPLOAD).single().header("X-Artracker-Key"))
        assertEquals(
            backend.requestsTo(FakeReplayBackend.UPLOAD).single().body.size,
            backend.requestsTo(FakeReplayBackend.ACK).single().json().getInt("size_bytes"),
        )
    }

    @Test
    fun ingestRequests_neverContainReplayChunkItems() {
        startRecording()
        closeBatch()
        awaitCalls(FakeReplayBackend.ACK, 1)
        ARTracker.track("after_replay")
        ARTracker.flush()
        awaitAll()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (backend.requestsTo(FakeReplayBackend.INGEST).isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)

        val ingest = backend.requestsTo(FakeReplayBackend.INGEST)
        assertTrue("the SDK must still send ordinary telemetry", ingest.isNotEmpty())
        for (call in ingest) {
            val items = JSONObject(gunzip(call.body)).getJSONArray("items")
            for (i in 0 until items.length()) assertFalse(items.getJSONObject(i).getString("name") == "replay_chunk")
        }
    }

    @Test
    fun secondBatch_continuesSeq() {
        startRecording()
        closeBatch()
        awaitCalls(FakeReplayBackend.ACK, 1)

        textView.text = "changed text"
        ARTrackerCore.instanceOrNull()!!.replayController!!.captureFrame()
        awaitAll()
        closeBatch()

        val chunkUrls = awaitCalls(FakeReplayBackend.ACK, 2).let { backend.requestsTo(FakeReplayBackend.CHUNK_URL) }
        assertEquals(listOf(0, 1), chunkUrls.map { it.json().getInt("chunk_seq") })
        assertEquals(1, chunkUrls.map { it.json().getString("session_id") }.distinct().size)
    }

    @Test
    fun uploadRequests_neverCarryApiKeyToUploadUrl() {
        startRecording()
        closeBatch()
        awaitCalls(FakeReplayBackend.ACK, 1)

        assertTrue(backend.requestsTo(FakeReplayBackend.CHUNK_URL).single().header("X-Artracker-Key") != null)
        assertTrue(backend.requestsTo(FakeReplayBackend.ACK).single().header("X-Artracker-Key") != null)
        assertNull(backend.requestsTo(FakeReplayBackend.UPLOAD).single().header("X-Artracker-Key"))
    }

    @Test
    fun storageNotConfigured_doesNotHotLoop() {
        backend.chunkUrl = { MockResponse().setResponseCode(503).addHeader("Retry-After", "30") }
        startRecording()
        closeBatch()
        awaitCalls(FakeReplayBackend.CHUNK_URL, 1)

        repeat(5) {
            ARTracker.flush()
            awaitAll()
            Thread.sleep(50)
        }
        Thread.sleep(500)

        assertEquals(1, backend.requestsTo(FakeReplayBackend.CHUNK_URL).size)
        assertTrue(backend.requestsTo(FakeReplayBackend.UPLOAD).isEmpty())
        // The batch is kept for a later attempt.
        val store = ReplayStore(app, Clock.SYSTEM)
        try {
            assertEquals(1, store.pendingCount())
        } finally {
            store.close()
        }
    }
}
