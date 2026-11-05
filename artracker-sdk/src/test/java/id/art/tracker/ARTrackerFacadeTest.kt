package id.art.tracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SdkLog
import id.art.tracker.core.SharedPrefsStore
import id.art.tracker.transport.FlushOutcome
import id.art.tracker.transport.FlusherRegistry
import id.art.tracker.transport.TransportConfigStore
import java.util.concurrent.CountDownLatch
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ARTrackerFacadeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val key = "art_live_" + "a".repeat(43)

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        context.deleteDatabase("artracker.db")
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        runCatching { server.shutdown() }
        context.deleteDatabase("artracker.db")
        SdkLog.enabled = false
    }

    private fun config(apiKey: String = key, debug: Boolean = false) = ARTrackerConfig(
        endpoint = server.url("/").toString().trimEnd('/'),
        apiKey = apiKey,
        environment = "test",
        appVersion = "1.2.3",
        debug = debug,
    )

    private fun start(apiKey: String = key, debug: Boolean = false) {
        server.start()
        ARTracker.init(context, config(apiKey, debug))
    }

    private fun awaitIdle() {
        runBlocking { withTimeout(15_000) { ARTrackerCore.instanceOrNull()!!.awaitIdle() } }
    }

    private fun body(request: RecordedRequest): JSONObject =
        JSONObject(GZIPInputStream(request.body.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) })

    private fun requests(): List<RecordedRequest> =
        (0 until server.requestCount).map { server.takeRequest(5, TimeUnit.SECONDS)!! }

    private fun sentItems(): List<JSONObject> = requests().flatMap { r ->
        val arr = body(r).getJSONArray("items")
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun List<JSONObject>.named(name: String) = filter { it.getString("name") == name }

    @Test
    fun beforeInit_allApisAreNoOps() {
        ARTracker.identify("u", mapOf("a" to 1))
        ARTracker.track("x", mapOf("a" to 1))
        ARTracker.screen("Home")
        ARTracker.log(LogLevel.INFO, "m", mapOf("a" to 1))
        ARTracker.captureException(RuntimeException("boom"), mapOf("a" to 1))
        ARTracker.flush()
        ARTracker.reset()
        ARTracker.optOut(true)
        ARTracker.optOut(false)
        ARTracker.startReplay()
        ARTracker.stopReplay()
        assertFalse(ARTracker.isInitialized())
        val d = ARTracker.diagnostics()
        assertFalse(d.initialized)
        assertNull(d.sessionId)
        assertEquals(0, d.pendingItems)
        assertNull(d.lastSendResult)
        assertFalse(d.disabled)
        // optOut before init must not have been persisted either.
        val prefs = SharedPrefsStore(context, TransportConfigStore.PREFS_NAME)
        assertEquals(0L, prefs.getLong(KillSwitch.KEY_OPTED_OUT, 0L))
    }

    @Test
    fun invalidConfig_staysUninitialized() {
        server.start()
        ARTracker.init(context, config(apiKey = "art_sec_" + "b".repeat(43), debug = true))
        assertFalse(ARTracker.isInitialized())
        assertNull(FlusherRegistry.current)
        ARTracker.track("x")
        assertEquals(0, ARTracker.diagnostics().pendingItems)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun init_isIdempotent() {
        start()
        val first = ARTrackerCore.instanceOrNull()
        assertNotNull(first)
        ARTracker.init(context, config(apiKey = "art_live_" + "z".repeat(43)))
        assertTrue(ARTracker.isInitialized())
        assertTrue(first === ARTrackerCore.instanceOrNull())
        server.enqueue(MockResponse().setResponseCode(202))
        ARTracker.track("x")
        ARTracker.flush()
        awaitIdle()
        assertEquals(key, server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-Artracker-Key"))
    }

    @Test
    fun trackAndFlush_reachesServer() {
        server.enqueue(MockResponse().setResponseCode(202))
        start()
        ARTracker.track("checkout_clicked", mapOf("total" to 120000))
        ARTracker.flush()
        awaitIdle()

        assertEquals(1, server.requestCount)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/ingest", request.path)
        assertEquals(key, request.getHeader("X-Artracker-Key"))
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        val envelope = body(request)
        assertEquals("artracker-android", envelope.getJSONObject("sdk").getString("name"))
        assertEquals("test", envelope.getJSONObject("context").getString("environment"))
        val items = envelope.getJSONArray("items").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
        val checkout = items.named("checkout_clicked").single()
        assertEquals("event", checkout.getString("type"))
        assertEquals(120000, checkout.getJSONObject("attributes").getInt("total"))
        val sessionStart = items.named("session_start").single()
        assertEquals("session", sessionStart.getString("type"))
        assertEquals("android", sessionStart.getJSONObject("attributes").getString("platform"))
        assertEquals(sessionStart.getString("session_id"), checkout.getString("session_id"))
        assertEquals("accepted", ARTracker.diagnostics().lastSendResult)
        assertEquals(0, ARTracker.diagnostics().pendingItems)
    }

    @Test
    fun identify_setsUserIdOnLaterItems() {
        server.enqueue(MockResponse().setResponseCode(202))
        start()
        ARTracker.identify("user-123", mapOf("plan" to "pro"))
        ARTracker.track("after")
        ARTracker.flush()
        awaitIdle()

        val items = sentItems()
        val identify = items.named("\$identify").single()
        assertEquals("event", identify.getString("type"))
        assertEquals("pro", identify.getJSONObject("attributes").getString("plan"))
        assertEquals("user-123", identify.getString("user_id"))
        assertEquals("user-123", items.named("after").single().getString("user_id"))
    }

    @Test
    fun reset_clearsUserAndChangesSession() {
        server.enqueue(MockResponse().setResponseCode(202))
        start()
        ARTracker.identify("user-123")
        val before = ARTracker.diagnostics().sessionId
        assertNotNull(before)
        ARTracker.reset()
        val after = ARTracker.diagnostics().sessionId
        assertNotNull(after)
        assertNotEquals(before, after)
        ARTracker.track("anonymous_again")
        ARTracker.flush()
        awaitIdle()

        val items = sentItems()
        val again = items.named("anonymous_again").single()
        assertFalse(again.has("user_id"))
        assertEquals(after, again.getString("session_id"))
    }

    @Test
    fun sessionEnd_isStampedWithEndedSessionId() {
        server.enqueue(MockResponse().setResponseCode(202))
        start()
        ARTracker.identify("user-9")
        val ended = ARTracker.diagnostics().sessionId!!
        ARTracker.reset()
        val fresh = ARTracker.diagnostics().sessionId!!
        ARTracker.flush()
        awaitIdle()

        val items = sentItems()
        val end = items.named("session_end").single()
        assertEquals(ended, end.getString("session_id"))
        assertTrue(end.getJSONObject("attributes").getLong("duration_ms") >= 0)
        // The ended session still belongs to the user who was signed in; the new one does not.
        assertEquals("user-9", end.getString("user_id"))
        val starts = items.named("session_start")
        assertEquals(setOf(ended, fresh), starts.map { it.getString("session_id") }.toSet())
        assertFalse(starts.single { it.getString("session_id") == fresh }.has("user_id"))
    }

    @Test
    fun concurrentTrack_fromEightThreads_allItemsQueued() {
        // Many items trigger the "50 pending" auto flush, so the server must answer without a queued response.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(202)
        }
        start()
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val threads = (0 until 8).map { t ->
            thread {
                ready.countDown()
                go.await()
                repeat(100) { i -> ARTracker.track("t$t", mapOf("i" to i)) }
            }
        }
        ready.await()
        go.countDown()
        threads.forEach { it.join(10_000) }
        awaitIdle()

        val pending = ARTracker.diagnostics().pendingItems
        val sent = sentItems()
        // 800 tracked items + the session items emitted by init (session_start, app_foreground),
        // each either still queued or delivered exactly once.
        assertEquals(802, pending + sent.size)
        assertEquals(sent.size, sent.map { it.getString("id") }.toSet().size)
    }

    @Test
    fun optOut_stopsSending() {
        server.enqueue(MockResponse().setResponseCode(202))
        start()
        ARTracker.track("before_opt_out")
        awaitIdle()
        assertTrue(ARTracker.diagnostics().pendingItems > 0)

        ARTracker.optOut(true)
        assertTrue(ARTracker.diagnostics().disabled)
        ARTracker.track("after_opt_out")
        ARTracker.screen("S")
        ARTracker.identify("u")
        ARTracker.log(LogLevel.ERROR, "m")
        ARTracker.captureException(RuntimeException("x"))
        ARTracker.flush()
        awaitIdle()

        assertEquals(0, server.requestCount)
        // Opting out also deleted what was queued, and nothing new was recorded.
        assertEquals(0, ARTracker.diagnostics().pendingItems)
        // Persisted in the same prefs file the background worker reads.
        val prefs = SharedPrefsStore(context, TransportConfigStore.PREFS_NAME)
        assertEquals(1L, prefs.getLong(KillSwitch.KEY_OPTED_OUT, 0L))

        ARTracker.optOut(false)
        assertFalse(ARTracker.diagnostics().disabled)
        ARTracker.track("resumed")
        ARTracker.flush()
        awaitIdle()
        assertTrue(sentItems().named("resumed").isNotEmpty())
        assertEquals(0L, prefs.getLong(KillSwitch.KEY_OPTED_OUT, 0L))
    }

    @Test
    fun optOut_true_thenImmediateFlush_sendsNothing() {
        start()
        ARTracker.track("queued")
        awaitIdle() // persisted in the queue
        val core = ARTrackerCore.instanceOrNull()!!

        ARTracker.optOut(true)
        // No awaitIdle(): the flusher must already see the opt-out even though nothing was written to disk yet.
        assertEquals(FlushOutcome.DISABLED, core.flusher.flushOnce())
        ARTracker.flush()
        awaitIdle()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun optOut_clearsPersistedItems_neverSentAfterOptIn() {
        server.enqueue(MockResponse().setResponseCode(202))
        start()
        ARTracker.track("before_opt_out")
        awaitIdle()
        assertTrue(ARTracker.diagnostics().pendingItems > 0)

        ARTracker.optOut(true)
        ARTracker.optOut(false)
        ARTracker.track("after_opt_in")
        ARTracker.flush()
        awaitIdle()

        assertEquals(listOf("after_opt_in"), sentItems().map { it.getString("name") })
    }

    @Test
    fun optOut_dropsItemsStillInThePipeline_neverSentAfterOptIn() {
        server.enqueue(MockResponse().setResponseCode(202))
        start()
        ARTracker.track("not_yet_persisted")

        ARTracker.optOut(true)
        ARTracker.optOut(false)
        ARTracker.track("after_opt_in")
        ARTracker.flush()
        awaitIdle()

        assertEquals(listOf("after_opt_in"), sentItems().map { it.getString("name") })
    }

    @Test
    fun optOut_false_thenFlush_sends() {
        server.enqueue(MockResponse().setResponseCode(202))
        start()
        ARTracker.optOut(true)
        ARTracker.optOut(false)
        // Immediately: the persisted flag may still be 1 on disk, the in-memory value must win everywhere.
        ARTracker.track("resumed")
        ARTracker.flush()
        awaitIdle()

        assertEquals(1, server.requestCount)
        assertEquals(listOf("resumed"), sentItems().map { it.getString("name") })
        assertFalse(ARTracker.diagnostics().disabled)
    }

    @Test
    fun optOut_fromBackgroundThread_isPersistedBeforeItReturns() {
        start()
        thread { ARTracker.optOut(true) }.join(10_000)
        // No awaitIdle(): a process death right now must not lose the choice.
        val prefs = SharedPrefsStore(context, TransportConfigStore.PREFS_NAME)
        assertEquals(1L, prefs.getLong(KillSwitch.KEY_OPTED_OUT, 0L))
    }

    @Test
    fun persistedOptOut_survivesReinitWithSamePrefs() {
        start()
        ARTracker.optOut(true)
        awaitIdle()
        ARTracker.resetForTest()

        ARTracker.init(context, config())
        assertTrue(ARTracker.isInitialized())
        assertTrue(ARTracker.diagnostics().disabled)
        ARTracker.track("ignored")
        ARTracker.flush()
        awaitIdle()
        assertEquals(0, ARTracker.diagnostics().pendingItems)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun persistedUnauthorizedTrip_survivesReinitWithSamePrefs() {
        server.enqueue(MockResponse().setResponseCode(401))
        start()
        ARTracker.track("first")
        ARTracker.flush()
        awaitIdle()
        assertTrue(ARTracker.diagnostics().disabled)
        ARTracker.resetForTest()

        ARTracker.init(context, config())
        assertTrue(ARTracker.diagnostics().disabled)
        ARTracker.track("ignored")
        ARTracker.flush()
        awaitIdle()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unauthorized_tripsKillSwitchAndLaterCallsAreNoOps() {
        server.enqueue(MockResponse().setResponseCode(401))
        start()
        ARTracker.track("first")
        ARTracker.flush()
        awaitIdle()

        val d = ARTracker.diagnostics()
        assertTrue(d.disabled)
        assertEquals("unauthorized", d.lastSendResult)
        val pending = d.pendingItems
        ARTracker.track("ignored")
        ARTracker.log(LogLevel.INFO, "ignored")
        ARTracker.flush()
        awaitIdle()
        assertEquals(pending, ARTracker.diagnostics().pendingItems)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun screen_log_and_captureException_emitExpectedItems() {
        server.enqueue(MockResponse().setResponseCode(202))
        start()
        ARTracker.screen("Checkout")
        ARTracker.log(LogLevel.WARN, "Promo API slow", mapOf("ms" to 2300))
        ARTracker.captureException(IllegalStateException("boom"), mapOf("where" to "test"))
        ARTracker.flush()
        awaitIdle()

        val items = sentItems()
        val screen = items.named("screen_view").single()
        assertEquals("Checkout", screen.getJSONObject("attributes").getString("screen"))
        val log = items.single { it.getString("type") == "log" && it.getString("level") == "warn" }
        assertEquals("Promo API slow", log.getString("message"))
        assertEquals(2300, log.getJSONObject("attributes").getInt("ms"))
        val ex = items.named("exception").single()
        assertEquals("error", ex.getString("level"))
        assertTrue(ex.getString("message").contains("IllegalStateException"))
        assertTrue(ex.getJSONObject("attributes").getString("stacktrace").contains("boom"))
        assertEquals("test", ex.getJSONObject("attributes").getString("where"))
    }

    @Test
    fun flusherRegistry_isSetOnInitAndClearedOnReset() {
        assertNull(FlusherRegistry.current)
        start()
        assertNotNull(FlusherRegistry.current)
        ARTracker.resetForTest()
        assertNull(FlusherRegistry.current)
        assertFalse(ARTracker.isInitialized())
    }

    @Test
    fun init_canRunAgainAfterResetForTest() {
        start()
        ARTracker.resetForTest()
        ARTracker.init(context, config())
        assertTrue(ARTracker.isInitialized())
    }

    @Test
    fun apiKeyNeverAppearsInLogOutput() {
        server.enqueue(MockResponse().setResponseCode(401))
        val badKey = "art_sec_" + "s".repeat(43)
        val otherKey = "art_live_" + "q".repeat(43)
        server.start()
        ARTracker.init(context, config(apiKey = badKey, debug = true))
        ARTracker.init(context, config(apiKey = key, debug = true))
        ARTracker.init(context, config(apiKey = otherKey, debug = true))
        ARTracker.track("x")
        ARTracker.flush()
        awaitIdle()

        val logs = ShadowLog.getLogs()
        assertTrue("expected the SDK to log something in debug mode", logs.isNotEmpty())
        for (entry in logs) {
            val text = entry.msg + (entry.throwable?.stackTraceToString() ?: "")
            for (secret in listOf(badKey, key, otherKey)) assertFalse(text, text.contains(secret))
            // Not even a fragment of the random part.
            assertFalse(text, text.contains("s".repeat(20)) || text.contains("a".repeat(20)) || text.contains("q".repeat(20)))
        }
    }

    @Test
    fun init_mainThreadCost_under50msInRobolectric() {
        // Indicative only: the real 2 ms budget is measured on a device in Task 25.
        server.start()
        val cfg = config()
        // Warm-up: the first init in a JVM pays for class loading/instrumentation, which is not what we measure.
        ARTracker.init(context, cfg)
        ARTracker.resetForTest()
        val t0 = System.nanoTime()
        ARTracker.init(context, cfg)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("init took ${elapsedMs}ms", elapsedMs < 50)
        assertTrue(ARTracker.isInitialized())
    }
}
