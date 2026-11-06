package id.art.tracker

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.testutil.QueueReader
import java.net.ServerSocket
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/** End to end: the free text of an item (name, message) is redacted in what is persisted, not only the attributes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemTextRedactionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
    }

    private fun init() {
        ARTracker.init(
            app,
            ARTrackerConfig(
                endpoint = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}",
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
            ),
        )
    }

    private fun persisted(): List<String> {
        runBlocking { withTimeout(30_000) { ARTrackerCore.instanceOrNull()!!.awaitIdle() } }
        return QueueReader.drain(app)
    }

    private fun byName(payloads: List<String>, name: String) =
        payloads.map { JSONObject(it) }.single { it.getString("name") == name }

    @Test
    fun logMessage_isRedactedInThePersistedPayload() {
        init()
        ARTracker.log(LogLevel.ERROR, "payment failed for jane@doe.com")

        val payloads = persisted()
        val raw = payloads.single { it.contains("\"name\":\"log\"") }

        assertFalse(raw, raw.contains("jane@doe.com"))
        assertTrue(raw, JSONObject(raw).getString("message").contains("j***@doe.com"))
    }

    @Test
    fun capturedExceptionMessage_isRedactedLikeItsStacktrace() {
        init()
        ARTracker.captureException(IllegalStateException("user 0812345678 / jane@doe.com not found"))

        val item = byName(persisted(), "exception")
        val message = item.getString("message")

        assertFalse(message, message.contains("jane@doe.com"))
        assertFalse(message, message.contains("0812345678"))
        assertFalse(item.toString(), item.toString().contains("jane@doe.com"))
        assertTrue(message, message.startsWith("java.lang.IllegalStateException: user "))
    }

    @Test
    fun trackedEventName_isRedacted() {
        init()
        ARTracker.track("opened jane@doe.com")

        val payloads = persisted()

        assertTrue(payloads.none { it.contains("jane@doe.com") })
        assertEquals("opened j***@doe.com", payloads.map { JSONObject(it).getString("name") }.single { it.startsWith("opened") })
    }

    @Test
    fun identifyAndLifecycleNames_stayIntact() {
        init()
        ARTracker.identify("u-1", mapOf("plan" to "pro"))
        ARTracker.screen("Home")

        val names = persisted().map { JSONObject(it).getString("name") }

        assertTrue(names.toString(), "\$identify" in names)
        assertTrue(names.toString(), "screen_view" in names)
        assertTrue(names.toString(), "session_start" in names)
    }

    @Test
    @Config(sdk = [30])
    fun exitInfoDescription_isRedactedInMessageAndAttribute() {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_ANR)
                .setTimestamp(System.currentTimeMillis() - 60_000)
                .setDescription("input dispatching timed out for jane@doe.com")
                .setImportance(100)
                .setProcessName(app.packageName)
                .build(),
        )
        init()

        val raw = persisted().single { it.contains("\"name\":\"anr\"") }
        val item = JSONObject(raw)

        assertFalse(raw, raw.contains("jane@doe.com"))
        assertTrue(item.getString("message").contains("j***@doe.com"))
        assertTrue(item.getJSONObject("attributes").getString("description").contains("j***@doe.com"))
    }
}
