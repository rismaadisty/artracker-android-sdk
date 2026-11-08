package id.art.tracker.telemetry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.LogLevel
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.Clock
import id.art.tracker.core.ItemFactory
import id.art.tracker.core.Iso8601
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SharedPrefsStore
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.InMemoryStore
import id.art.tracker.transport.TransportConfigStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ExitInfoCollectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(1_700_000_100_000)
    private val store = InMemoryStore()
    private val items = ItemFactory(clock, { "current-session" }, { null })
    private val collector get() = ExitInfoCollector(context, store, items, clock)
    private val activityManager get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private fun addExit(
        reason: Int,
        timestamp: Long,
        trace: InputStream? = null,
        description: String = "desc",
        process: String = "id.art.tracker.test",
    ) {
        val builder = ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setTimestamp(timestamp)
            .setDescription(description)
            .setImportance(100)
            .setPss(2048)
            .setRss(4096)
            .setProcessName(process)
        if (trace != null) builder.setTraceInputStream(trace)
        shadowOf(activityManager).addApplicationExitInfo(builder.build())
    }

    @Test
    fun anr_becomesFatalItem() {
        addExit(ApplicationExitInfo.REASON_ANR, 1_700_000_000_000, ByteArrayInputStream("main thread blocked".toByteArray()))

        val item = collector.collect().single()

        assertEquals("anr", item.name)
        assertEquals(LogLevel.FATAL, item.level)
        assertEquals("current-session", item.sessionId)
        assertEquals("the item is placed at the time of the exit", 1_700_000_000_000, item.timestampMs)
        assertEquals(ApplicationExitInfo.REASON_ANR, item.attributes["reason"])
        assertEquals("anr", item.attributes["reason_name"])
        assertEquals("desc", item.attributes["description"])
        assertEquals(100, item.attributes["importance"])
        assertEquals(2048L, item.attributes["pss"])
        assertEquals(4096L, item.attributes["rss"])
        assertEquals(1_700_000_000_000, item.attributes["exit_timestamp"])
        assertEquals("id.art.tracker.test", item.attributes["process_name"])
        assertEquals("main thread blocked", item.attributes["trace"])
        assertEquals(1_700_000_000_000, store.getLong("exitinfo_last_ts", 0))
    }

    @Test
    fun nativeCrash_isFatal() {
        addExit(ApplicationExitInfo.REASON_CRASH_NATIVE, 1_700_000_000_000, ByteArrayInputStream("tombstone".toByteArray()))

        val item = collector.collect().single()

        assertEquals("native_crash", item.name)
        assertEquals(LogLevel.FATAL, item.level)
        assertEquals("tombstone", item.attributes["trace"])
    }

    @Test
    fun lowMemory_isWarn() {
        addExit(ApplicationExitInfo.REASON_LOW_MEMORY, 1_700_000_000_000, ByteArrayInputStream("ignored".toByteArray()))

        val item = collector.collect().single()

        assertEquals("low_memory_kill", item.name)
        assertEquals(LogLevel.WARN, item.level)
        assertFalse("no trace for a low memory kill", item.attributes.containsKey("trace"))
    }

    @Test
    fun crashJava_isIgnored() {
        addExit(ApplicationExitInfo.REASON_CRASH, 1_700_000_000_000)
        addExit(ApplicationExitInfo.REASON_USER_REQUESTED, 1_700_000_000_500)

        assertTrue(collector.collect().isEmpty())
        assertEquals("ignored reasons still advance the timestamp", 1_700_000_000_500, store.getLong("exitinfo_last_ts", 0))
    }

    @Test
    fun secondCollect_returnsNothingNew() {
        addExit(ApplicationExitInfo.REASON_ANR, 1_700_000_000_000)
        assertEquals(1, collector.collect().size)

        assertTrue(collector.collect().isEmpty())

        addExit(ApplicationExitInfo.REASON_CRASH_NATIVE, 1_700_000_001_000)
        assertEquals(listOf("native_crash"), collector.collect().map { it.name })
    }

    @Test
    fun onlyEntriesNewerThanTheLastProcessedOnesAreCollected() {
        store.putLong("exitinfo_last_ts", 1_700_000_000_000)
        addExit(ApplicationExitInfo.REASON_ANR, 1_700_000_000_000) // equal: already processed
        addExit(ApplicationExitInfo.REASON_ANR, 1_699_999_999_999)
        addExit(ApplicationExitInfo.REASON_CRASH_NATIVE, 1_700_000_000_001)

        assertEquals(listOf("native_crash"), collector.collect().map { it.name })
    }

    @Test
    @Config(sdk = [29])
    fun api29_returnsEmpty() {
        assertTrue(collector.collect().isEmpty())
        assertEquals(0L, store.getLong("exitinfo_last_ts", 0))
    }

    @Test
    fun traceCappedAt16KB() {
        val big = "x".repeat(100 * 1024)
        addExit(ApplicationExitInfo.REASON_ANR, 1_700_000_000_000, ByteArrayInputStream(big.toByteArray()))

        val trace = collector.collect().single().attributes["trace"] as String

        assertTrue("trace is ${trace.length} chars", trace.toByteArray().size <= 16 * 1024)
        assertTrue(trace.length > 16 * 1024 - 8)
    }

    @Test
    fun traceCut_inTheMiddleOfAMultiByteChar_isLossyNotFatal() {
        // 3-byte characters: 16384 is not a multiple of 3, so the cut lands inside one.
        val text = "€".repeat(10_000)
        addExit(ApplicationExitInfo.REASON_ANR, 1_700_000_000_000, ByteArrayInputStream(text.toByteArray()))

        val trace = collector.collect().single().attributes["trace"] as String

        assertTrue(trace.startsWith("€"))
        assertFalse("no replacement character at the cut", trace.contains('�'))
        assertTrue(trace.toByteArray().size <= 16 * 1024)
    }

    @Test
    fun traceStreamThrows_doesNotThrow() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom")
        }
        addExit(ApplicationExitInfo.REASON_ANR, 1_700_000_000_000, broken)

        val item = collector.collect().single()

        assertEquals("anr", item.name)
        assertNull(item.attributes["trace"])
        assertFalse(item.attributes.containsKey("trace"))
    }

    @Test
    fun traceStream_isClosed() {
        var closed = false
        val stream = object : ByteArrayInputStream("t".toByteArray()) {
            override fun close() {
                closed = true
            }
        }
        addExit(ApplicationExitInfo.REASON_ANR, 1_700_000_000_000, stream)

        collector.collect()

        assertTrue(closed)
    }

    @Test
    fun markProcessed_advancesWithoutItems() {
        addExit(ApplicationExitInfo.REASON_ANR, 1_700_000_000_000)

        collector.markProcessed()

        assertEquals(1_700_000_000_000, store.getLong("exitinfo_last_ts", 0))
        assertTrue(collector.collect().isEmpty())
    }

    @Test
    fun exitOlderThanSevenDays_isSkippedButMarkedProcessed() {
        val eightDays = clock.nowMs() - 8 * 86_400_000L
        addExit(ApplicationExitInfo.REASON_ANR, eightDays)

        assertTrue(collector.collect().isEmpty())

        assertEquals("the old entry is remembered as processed", eightDays, store.getLong("exitinfo_last_ts", 0))
    }

    @Test
    fun exitFromYesterday_isReported_andOldOnesNextToItAreNot() {
        val oneDay = clock.nowMs() - 86_400_000L
        val eightDays = clock.nowMs() - 8 * 86_400_000L
        addExit(ApplicationExitInfo.REASON_ANR, eightDays)
        addExit(ApplicationExitInfo.REASON_CRASH_NATIVE, oneDay)

        val result = collector.collect()

        assertEquals(listOf("native_crash"), result.map { it.name })
        assertEquals(oneDay, store.getLong("exitinfo_last_ts", 0))
        assertTrue("nothing is reported twice", collector.collect().isEmpty())
    }

    @Test
    fun sevenDayWindow_doesNotMoveTheStoredTimestampBackwards() {
        val recent = clock.nowMs() - 1_000
        store.putLong("exitinfo_last_ts", recent)
        addExit(ApplicationExitInfo.REASON_ANR, clock.nowMs() - 9 * 86_400_000L)

        assertTrue(collector.collect().isEmpty())

        assertEquals(recent, store.getLong("exitinfo_last_ts", 0))
    }

    @Test
    fun markProcessed_alsoCoversEntriesOlderThanTheWindow() {
        val old = clock.nowMs() - 30 * 86_400_000L
        addExit(ApplicationExitInfo.REASON_ANR, old)

        collector.markProcessed()

        assertEquals(old, store.getLong("exitinfo_last_ts", 0))
    }

    // region wiring (Robolectric + MockWebServer)

    private val key = "art_live_" + "a".repeat(43)
    private val server = MockWebServer()
    private val originalDefault = Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        context.deleteDatabase("artracker.db")
        context.getSharedPreferences(TransportConfigStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        Thread.setDefaultUncaughtExceptionHandler(originalDefault)
        runCatching { server.shutdown() }
        context.deleteDatabase("artracker.db")
    }

    private fun config(endpoint: String) = ARTrackerConfig(
        endpoint = endpoint,
        apiKey = key,
        environment = "test",
        appVersion = "1.2.3",
    )

    private fun startServer(): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(202)
        }
        server.start()
        return server.url("/").toString().trimEnd('/')
    }

    private fun awaitIdle() {
        runBlocking { withTimeout(15_000) { ARTrackerCore.instanceOrNull()!!.awaitIdle() } }
    }

    private fun sentItems(): List<JSONObject> = (0 until server.requestCount).flatMap {
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val text = GZIPInputStream(request.body.inputStream()).use { s -> String(s.readBytes(), Charsets.UTF_8) }
        val arr = JSONObject(text).getJSONArray("items")
        (0 until arr.length()).map { i -> arr.getJSONObject(i) }
    }

    private fun lastTs() = SharedPrefsStore(context, TransportConfigStore.PREFS_NAME).getLong("exitinfo_last_ts", 0)

    @Test
    fun init_sendsTheAnrOfThePreviousProcess() {
        val exitAt = System.currentTimeMillis() - 3_600_000L
        addExit(
            ApplicationExitInfo.REASON_ANR,
            exitAt,
            ByteArrayInputStream("at Foo.bar(Foo.kt:1) mail jane.doe@example.com".toByteArray()),
        )
        val endpoint = startServer()

        ARTracker.init(context, config(endpoint))
        awaitIdle()

        val anr = sentItems().single { it.getString("name") == "anr" }
        assertEquals("log", anr.getString("type"))
        assertEquals("fatal", anr.getString("level"))
        assertEquals(Iso8601.format(exitAt), anr.getString("timestamp"))
        val attrs = anr.getJSONObject("attributes")
        assertEquals("at Foo.bar(Foo.kt:1) mail j***@example.com", attrs.getString("trace"))
        assertEquals(exitAt, lastTs())
        assertEquals(0, ARTracker.diagnostics().pendingItems)
    }

    @Test
    fun optedOut_advancesTimestampWithoutItems() {
        KillSwitch(SharedPrefsStore(context, TransportConfigStore.PREFS_NAME), Clock.SYSTEM).optedOut = true
        val exitAt = System.currentTimeMillis() - 3_600_000L
        addExit(ApplicationExitInfo.REASON_ANR, exitAt)
        val endpoint = startServer()

        ARTracker.init(context, config(endpoint))
        awaitIdle()

        assertEquals(exitAt, lastTs())
        assertEquals(0, server.requestCount)
        assertEquals(0, ARTracker.diagnostics().pendingItems)

        // Opting back in later must not resurrect the old exit.
        ARTracker.resetForTest()
        KillSwitch(SharedPrefsStore(context, TransportConfigStore.PREFS_NAME), Clock.SYSTEM).optedOut = false
        ARTracker.init(context, config(endpoint))
        awaitIdle()
        assertNotNull(ARTrackerCore.instanceOrNull())
        assertTrue(sentItems().none { it.getString("name") == "anr" })
    }

    // endregion
}
