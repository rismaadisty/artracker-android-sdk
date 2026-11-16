package id.art.tracker

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.core.ARTrackerCore
import java.io.ByteArrayInputStream
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/** `ARTracker.setGlobalProperties` / `clearGlobalProperties`, seen from what reaches the server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlobalPropertiesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val key = "art_live_" + "a".repeat(43)

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        context.deleteDatabase("artracker.db")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(202)
        }
        server.start()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        runCatching { server.shutdown() }
        context.deleteDatabase("artracker.db")
    }

    private fun start(optOut: Boolean = false) {
        ARTracker.init(
            context,
            ARTrackerConfig(
                endpoint = server.url("/").toString().trimEnd('/'),
                apiKey = key,
                environment = "test",
                appVersion = "1.2.3",
            ),
        )
        awaitIdle()
        if (optOut) ARTracker.optOut(true)
    }

    private fun awaitIdle() {
        runBlocking { withTimeout(15_000) { ARTrackerCore.instanceOrNull()!!.awaitIdle() } }
    }

    private fun sentItems(): List<JSONObject> {
        val requests = (0 until server.requestCount).map { server.takeRequest(5, TimeUnit.SECONDS)!! }
        return requests.flatMap { r ->
            val text = GZIPInputStream(r.body.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
            val arr = JSONObject(text).getJSONArray("items")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }

    private fun flushAndRead(): List<JSONObject> {
        ARTracker.flush()
        awaitIdle()
        return sentItems()
    }

    private fun List<JSONObject>.named(name: String) = filter { it.getString("name") == name }

    private fun JSONObject.attr(key: String): Any? = getJSONObject("attributes").opt(key)

    @Test
    fun globals_rideOnTrackLogAndScreen() {
        start()
        ARTracker.setGlobalProperties(mapOf("tenant" to "acme"))
        ARTracker.track("t")
        ARTracker.log(LogLevel.INFO, "m")
        ARTracker.screen("Home")

        val items = flushAndRead()

        assertEquals("acme", items.named("t").single().attr("tenant"))
        assertEquals("acme", items.named("log").single().attr("tenant"))
        assertEquals("acme", items.named("screen_view").single().attr("tenant"))
    }

    @Test
    fun sessionStart_afterReset_carriesGlobals_andResetDoesNotClearThem() {
        start()
        ARTracker.setGlobalProperties(mapOf("tenant" to "acme"))
        ARTracker.reset()
        ARTracker.track("after")

        val items = flushAndRead()

        assertEquals("acme", items.named("session_start").last().attr("tenant"))
        assertEquals("acme", items.named("after").single().attr("tenant"))
    }

    @Test
    fun set_replacesTheWholeSet() {
        start()
        ARTracker.setGlobalProperties(mapOf("a" to 1, "b" to 2))
        ARTracker.setGlobalProperties(mapOf("b" to 3))
        ARTracker.track("t")

        val item = flushAndRead().named("t").single()

        assertFalse(item.getJSONObject("attributes").has("a"))
        assertEquals(3, item.attr("b"))
    }

    @Test
    fun clear_removesThem() {
        start()
        ARTracker.setGlobalProperties(mapOf("tenant" to "acme"))
        ARTracker.clearGlobalProperties()
        ARTracker.track("t")

        assertFalse(flushAndRead().named("t").single().getJSONObject("attributes").has("tenant"))
    }

    @Test
    fun callerMutationAfterSet_isNotSeen() {
        start()
        val m = mutableMapOf<String, Any?>("k" to "v1")
        ARTracker.setGlobalProperties(m)
        m["k"] = "v2"
        ARTracker.track("t")

        assertEquals("v1", flushAndRead().named("t").single().attr("k"))
    }

    @Test
    fun itemsTrackedBeforeSet_doNotGetTheNewValue() {
        start()
        ARTracker.track("before")
        ARTracker.setGlobalProperties(mapOf("v" to 1)) // no waiting in between: the pipeline may still lag
        ARTracker.track("after")

        val items = flushAndRead()

        assertFalse(items.named("before").single().getJSONObject("attributes").has("v"))
        assertEquals(1, items.named("after").single().attr("v"))
    }

    @Test
    fun setWhileOptedOut_isKept_andAppliesAfterOptIn() {
        start(optOut = true)
        ARTracker.setGlobalProperties(mapOf("tenant" to "acme"))
        ARTracker.track("dropped")
        ARTracker.optOut(false)
        ARTracker.track("x")

        val items = flushAndRead()

        assertTrue(items.named("dropped").isEmpty())
        assertEquals("acme", items.named("x").single().attr("tenant"))
    }

    @Test
    fun exitInfoFromAnEarlierProcess_getsNoGlobals() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_ANR)
                .setTimestamp(System.currentTimeMillis() - 60_000)
                .setDescription("desc")
                .setImportance(100)
                .setPss(2048)
                .setRss(4096)
                .setProcessName("id.art.tracker.test")
                .setTraceInputStream(ByteArrayInputStream("main thread blocked".toByteArray()))
                .build(),
        )
        start()
        ARTracker.setGlobalProperties(mapOf("tenant" to "acme"))
        ARTracker.track("after")

        val items = flushAndRead()

        assertFalse(items.named("anr").single().getJSONObject("attributes").has("tenant"))
        assertEquals("acme", items.named("after").single().attr("tenant"))
    }

    @Test
    fun hostileMap_keepsThePreviousGlobals() {
        start()
        ARTracker.setGlobalProperties(mapOf("tenant" to "acme"))
        val hostile = object : AbstractMap<String, Any?>() {
            override val entries: Set<Map.Entry<String, Any?>> get() = throw IllegalStateException("hostile map")
        }
        ARTracker.setGlobalProperties(hostile) // must not throw into the host
        ARTracker.track("t")

        assertEquals("acme", flushAndRead().named("t").single().attr("tenant"))
    }

    @Test
    fun concurrentSetAndTrack_everyItemSeesOneWholeSnapshot() {
        start()
        val a = thread { repeat(500) { ARTracker.setGlobalProperties(mapOf("v" to "a", "w" to "a")) } }
        val b = thread { repeat(500) { ARTracker.setGlobalProperties(mapOf("v" to "b", "w" to "b")) } }
        val c = thread { repeat(500) { ARTracker.track("t") } }
        listOf(a, b, c).forEach { it.join(30_000) }

        val tracked = flushAndRead().named("t")

        assertTrue("some items were tracked", tracked.isNotEmpty())
        for (item in tracked) {
            val attrs = item.getJSONObject("attributes")
            if (attrs.has("v")) assertEquals("a snapshot is never a mix of two sets", attrs.getString("v"), attrs.getString("w"))
        }
    }

    @Test
    fun callsBeforeInit_areIgnored_andNothingIsKept() {
        ARTracker.setGlobalProperties(mapOf("a" to 1))
        ARTracker.clearGlobalProperties()
        ARTracker.setGlobalProperties(mapOf("a" to 1)) // before init: not stored
        start()
        ARTracker.track("t")

        assertFalse(flushAndRead().named("t").single().getJSONObject("attributes").has("a"))
    }

    @Test
    fun isCallableAsAJavaStatic() {
        val set = ARTracker::class.java.getMethod("setGlobalProperties", Map::class.java)
        val clear = ARTracker::class.java.getMethod("clearGlobalProperties")
        assertTrue(Modifier.isStatic(set.modifiers))
        assertTrue(Modifier.isStatic(clear.modifiers))
    }
}
