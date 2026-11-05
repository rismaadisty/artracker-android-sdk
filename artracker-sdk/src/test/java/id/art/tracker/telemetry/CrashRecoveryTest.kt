package id.art.tracker.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.Clock
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SharedPrefsStore
import id.art.tracker.transport.TransportConfigStore
import id.art.tracker.testutil.FakeQueue
import id.art.tracker.transport.ItemQueue
import id.art.tracker.transport.StoredItem
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = "art_live_" + "a".repeat(43)
    private val originalDefault = Thread.getDefaultUncaughtExceptionHandler()
    private val quietDefault = Thread.UncaughtExceptionHandler { _, _ -> }
    private val server = MockWebServer()
    private val crashDir get() = File(context.filesDir, "artracker/crash")

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        // Whatever is the default handler when the SDK starts is the one a crash is chained to: keep it silent.
        Thread.setDefaultUncaughtExceptionHandler(quietDefault)
        context.deleteDatabase("artracker.db")
        crashDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        Thread.setDefaultUncaughtExceptionHandler(originalDefault)
        runCatching { server.shutdown() }
        context.deleteDatabase("artracker.db")
        crashDir.deleteRecursively()
    }

    private fun crashJson(id: String, message: String = "java.lang.RuntimeException: boom") =
        """{"id":"$id","type":"log","name":"crash","level":"fatal","message":"$message",""" +
            """"timestamp":"2025-11-05T08:33:18.050Z","session_id":"s","attributes":{"fingerprint":"f"}}"""

    private fun claimAll(queue: FakeQueue): List<StoredItem> = queue.claimBatch(maxItems = 100)?.items ?: emptyList()

    @Test
    fun crashFile_becomesPriority0Item_andFileDeleted() {
        val store = CrashStore(crashDir)
        val queue = FakeQueue()
        val json = crashJson("0195-crash-1")
        store.writeSync("0195-crash-1", json)

        val recovered = CrashRecovery.recover(store, queue)

        assertEquals(1, recovered)
        val item = claimAll(queue).single()
        assertEquals("0195-crash-1", item.id)
        assertEquals(0, item.priority)
        assertEquals(json, item.payloadJson)
        assertTrue(item.createdAtMs > 0)
        assertTrue(store.pending().isEmpty())
        assertEquals(emptyList<String>(), crashDir.list()!!.toList())
    }

    @Test
    fun corruptFiles_areDeleted_notInserted_andDoNotPoisonTheRest() {
        val store = CrashStore(crashDir)
        val queue = FakeQueue()
        crashDir.mkdirs()
        File(crashDir, "0001.json").writeText("{\"id\":\"0001\",\"type\":\"log\",\"na") // truncated
        File(crashDir, "0002.json").writeText("not json at all")
        File(crashDir, "0003.json").writeBytes(byteArrayOf(0, 1, 2, -1, -2))
        File(crashDir, "0004.json").writeText("{\"type\":\"log\",\"name\":\"crash\"}") // no id
        File(crashDir, "0005.json").writeText("") // empty
        File(crashDir, "0006.json").writeText("[1,2]") // not an object
        store.writeSync("0007", crashJson("0007"))

        val recovered = CrashRecovery.recover(store, queue)

        assertEquals(1, recovered)
        assertEquals(listOf("0007"), claimAll(queue).map { it.id })
        assertTrue("every file is gone", crashDir.list()!!.isEmpty())
    }

    @Test
    fun insertFailure_keepsTheFile_forTheNextStart() {
        val store = CrashStore(crashDir)
        store.writeSync("0001", crashJson("0001"))
        store.writeSync("0002", crashJson("0002"))
        val good = FakeQueue()
        var calls = 0
        val flaky = object : ItemQueue by good {
            override fun insert(items: List<StoredItem>) {
                if (calls++ == 0) throw IllegalStateException("db locked") else good.insert(items)
            }
        }

        val recovered = CrashRecovery.recover(store, flaky)

        assertEquals(1, recovered)
        assertEquals(listOf("0001.json"), store.pending().map { it.name })
        assertEquals(listOf("0002"), claimAll(good).map { it.id })

        // Next start: the left-over file is recovered.
        assertEquals(1, CrashRecovery.recover(store, good))
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun recoverTwice_isIdempotent() {
        val store = CrashStore(crashDir)
        val queue = FakeQueue()
        store.writeSync("0001", crashJson("0001"))
        assertEquals(1, CrashRecovery.recover(store, queue))
        assertEquals(0, CrashRecovery.recover(store, queue))
        assertEquals(1, queue.pendingCount())
    }

    // region end to end (Robolectric + MockWebServer)

    private fun config(endpoint: String) = ARTrackerConfig(
        endpoint = endpoint,
        apiKey = key,
        environment = "test",
        appVersion = "1.2.3",
    )

    private fun awaitIdle() {
        runBlocking { withTimeout(15_000) { ARTrackerCore.instanceOrNull()!!.awaitIdle() } }
    }

    private fun itemsOf(request: RecordedRequest): List<JSONObject> {
        val text = GZIPInputStream(request.body.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
        val arr = JSONObject(text).getJSONArray("items")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun sentItems(): List<JSONObject> =
        (0 until server.requestCount).flatMap { itemsOf(server.takeRequest(5, TimeUnit.SECONDS)!!) }

    private fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(202)
        }
        server.start()
    }

    @Test
    fun offlineCrash_sentOnNextInit() {
        // Init #1: the endpoint is unreachable (nothing listens on port 9).
        ARTracker.init(context, config("http://127.0.0.1:9"))
        awaitIdle()
        ARTracker.identify("user-42")
        ARTracker.screen("Checkout")
        ARTracker.track("pay_clicked")
        awaitIdle()
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue("the SDK installed its crash handler", installed is CrashHandler)

        installed!!.uncaughtException(Thread.currentThread(), RuntimeException("kaboom for jane.doe@example.com"))

        assertEquals("one crash file on disk", 1, crashDir.listFiles { f -> f.name.endsWith(".json") }!!.size)
        assertTrue(crashDir.listFiles()!!.none { it.name.endsWith(".tmp") })
        ARTracker.resetForTest()
        assertSame("shutdown puts the previous handler back", quietDefault, Thread.getDefaultUncaughtExceptionHandler())

        // Init #2: a live server.
        startServer()
        ARTracker.init(context, config(server.url("/").toString().trimEnd('/')))
        awaitIdle()

        val crash = sentItems().single { it.getString("name") == "crash" }
        assertEquals("log", crash.getString("type"))
        assertEquals("fatal", crash.getString("level"))
        assertEquals("java.lang.RuntimeException: kaboom for j***@example.com", crash.getString("message"))
        assertEquals("user-42", crash.getString("user_id"))
        assertTrue(crash.getString("session_id").isNotBlank())
        val attrs = crash.getJSONObject("attributes")
        assertEquals("Checkout", attrs.getString("screen"))
        assertEquals("1.2.3", attrs.getString("app_version"))
        assertFalse(attrs.getBoolean("handled"))
        val crumbs = attrs.getJSONArray("breadcrumbs")
        assertEquals(listOf("screen", "event"), (0 until crumbs.length()).map { crumbs.getJSONObject(it).getString("type") })
        assertEquals(0, crashDir.list()?.size ?: 0)
        assertEquals(0, ARTracker.diagnostics().pendingItems)
    }

    @Test
    fun crashWhileOptedOut_writesNothing_butStillDelegates() {
        val calls = ArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> calls += e }
        ARTracker.init(context, config("http://127.0.0.1:9"))
        awaitIdle()
        ARTracker.optOut(true)

        val boom = RuntimeException("x")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        assertEquals(listOf<Throwable>(boom), calls)
        assertTrue(crashDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun crashFileFoundWhileOptedOut_isDeleted_notSent() {
        KillSwitch(SharedPrefsStore(context, TransportConfigStore.PREFS_NAME), Clock.SYSTEM).optedOut = true
        CrashStore(crashDir).writeSync("0001", crashJson("0001"))
        startServer()

        ARTracker.init(context, config(server.url("/").toString().trimEnd('/')))
        awaitIdle()

        assertEquals(0, crashDir.list()?.size ?: 0)
        assertEquals(0, server.requestCount)
        assertEquals(0, ARTracker.diagnostics().pendingItems)
    }

    // endregion
}
