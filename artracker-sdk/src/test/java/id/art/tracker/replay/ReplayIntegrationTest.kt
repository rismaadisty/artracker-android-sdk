package id.art.tracker.replay

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.ReplayConfig
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** End to end: ARTracker.init with replay enabled -> activity -> flush -> the server gets a valid replay_chunk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayIntegrationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ClassAvailability.resetOverrides()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        runCatching { server.shutdown() }
        app.deleteDatabase("artracker.db")
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

    @Test
    fun initWithReplay_resumeActivity_flush_serverReceivesReplayChunk() {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(202)) }
        server.start()
        ARTracker.init(
            app,
            ARTrackerConfig(
                endpoint = server.url("/").toString().trimEnd('/'),
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
                replay = ReplayConfig(enabled = true, sampleRate = 1.0),
            ),
        )
        ProcessLifecycleTestUtil.start()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(TextView(activity).apply { text = "hello replay" })
        idle(1_000)
        awaitAll()
        idle(5_000) // chunk flush timer
        awaitAll()
        ARTracker.flush()
        awaitAll()

        val items = (0 until server.requestCount).flatMap {
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            val body = GZIPInputStream(request.body.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
            val arr = JSONObject(body).getJSONArray("items")
            (0 until arr.length()).map { i -> arr.getJSONObject(i) }
        }
        val chunk = items.first { it.getString("name") == "replay_chunk" }
        assertEquals("session", chunk.getString("type"))
        val attributes = chunk.getJSONObject("attributes")
        assertTrue(attributes.getBoolean("full_snapshot"))
        assertEquals("android", attributes.getString("platform"))
        val events = attributes.getJSONArray("events")
        assertEquals(4, events.getJSONObject(0).getInt("type"))
        assertEquals(2, events.getJSONObject(1).getInt("type"))
        assertTrue(attributes.toString().contains("hello replay"))
        assertFalse(attributes.toString().contains("[max_depth]"))
    }
}
