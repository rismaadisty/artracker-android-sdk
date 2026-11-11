package id.art.tracker.perf

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.BeforeSend
import id.art.tracker.ItemType
import id.art.tracker.NetworkConfig
import id.art.tracker.PerformanceConfig
import id.art.tracker.SendRequest
import id.art.tracker.SendResponse
import id.art.tracker.TelemetryItem
import id.art.tracker.ARTrackerSender
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.lifecycle.ActivityTracker
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.FakeDeviceProbe
import id.art.tracker.testutil.FakeProcessReader
import id.art.tracker.transport.ReplayStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerfCoreIntegrationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(1_000_000)
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
    private val requests = CopyOnWriteArrayList<SendRequest>()

    /** Releases one sampling interval per element; [delays] counts how often the loop has gone to wait. */
    private val gate = Channel<Unit>(Channel.UNLIMITED)
    private val delays = AtomicInteger()
    private var core: ARTrackerCore? = null

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
    }

    @After
    fun tearDown() {
        core?.shutdown()
        ARTrackerCore.clear()
        ARTracker.resetForTest()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
    }

    private fun startCore(performance: PerformanceConfig): ARTrackerCore {
        val config = ARTrackerConfig(
            endpoint = "https://ingest.example.com",
            apiKey = "art_live_" + "a".repeat(43),
            environment = "test",
            appVersion = "1.0",
            beforeSend = BeforeSend { it.also(seen::add) },
            network = NetworkConfig(sender = ARTrackerSender { r -> requests.add(r); SendResponse(202) }),
            performance = performance,
        )
        val c = ARTrackerCore(
            app, config, clock,
            perfFactory = { owner ->
                val probe = FakeDeviceProbe(cores = 4)
                val sampler = PerfSampler(FakeProcessReader(null, "100 256 0 0 0 0 0"), null, probe, clock) { clock.nowMs() }
                owner.newPerfMonitor(sampler, probe)
            },
            perfDelay = { delays.incrementAndGet(); gate.receive() },
        )
        ARTrackerCore.install(c)
        c.start()
        core = c
        return c
    }

    private fun enabled() = PerformanceConfig(enabled = true, sampleIntervalMs = 1_000, trackFrames = false)

    /** Runs [n] sampling intervals and returns once the loop is waiting for the next one (so n ticks are done). */
    private fun tick(n: Int) {
        val target = delays.get() + n
        repeat(n) { gate.trySend(Unit); clock.advance(1_000) }
        waitUntil { delays.get() >= target }
    }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(5)
        }
    }

    private fun settle() = runBlocking { withTimeout(15_000) { core!!.awaitIdle() } }

    private fun perfItems() = seen.filter { it.type == ItemType.PERF }

    private fun samples(item: TelemetryItem) = (item.attributes["samples"] as List<*>).size

    private fun names(request: SendRequest): List<String> {
        val items = JSONObject(GZIPInputStream(request.body.inputStream()).use { String(it.readBytes()) }).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("name") }
    }

    @Test
    fun `disabled creates nothing`() {
        val c = startCore(PerformanceConfig())
        c.onForeground()
        app.onTrimMemory(10)
        gate.trySend(Unit)
        Thread.sleep(100)
        settle()
        assertNull(c.perf)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "artracker-perf-frames" })
        assertEquals(0, delays.get())
        assertTrue(seen.none { it.type == ItemType.PERF || it.name == "perf.memory_warning" })
    }

    @Test
    fun `enabled core samples while in the foreground`() {
        val c = startCore(enabled())
        assertNotNull(c.perf)
        c.onForeground()
        waitUntil { delays.get() == 1 }
        tick(30)
        settle()
        val items = perfItems()
        assertEquals(3, items.size)
        assertEquals(30, items.sumOf(::samples))
    }

    @Test
    fun `background enqueues perf item before flushNow`() {
        val c = startCore(enabled())
        c.onForeground()
        waitUntil { delays.get() == 1 }
        tick(4)
        c.onBackground()
        settle()
        val batch = requests.single { "perf.samples" in names(it) }
        assertTrue("app_background" in names(batch))
        assertEquals(1, names(batch).count { it == "perf.samples" })
        assertEquals(4, perfItems().single().let(::samples))
    }

    @Test
    fun `no sampling after background`() {
        val c = startCore(enabled())
        c.onForeground()
        waitUntil { delays.get() == 1 }
        tick(2)
        c.onBackground()
        settle()
        val before = perfItems().size
        val delaysBefore = delays.get()
        repeat(60) { gate.trySend(Unit) }
        Thread.sleep(200)
        settle()
        assertEquals(before, perfItems().size)
        assertEquals(delaysBefore, delays.get())
    }

    @Test
    fun `foreground again resumes sampling`() {
        val c = startCore(enabled())
        c.onForeground()
        waitUntil { delays.get() == 1 }
        tick(2)
        c.onBackground()
        val d = delays.get()
        c.onForeground()
        waitUntil { delays.get() == d + 1 }
        tick(10)
        settle()
        assertEquals(12, perfItems().sumOf(::samples))
    }

    @Test
    fun `session end flushes samples under the ending session`() {
        val c = startCore(enabled())
        c.onForeground()
        waitUntil { delays.get() == 1 }
        tick(3)
        val old = c.session.peekId()
        c.reset()
        settle()
        val item = perfItems().single()
        assertEquals(old, item.sessionId)
        assertEquals(3, samples(item))
    }

    @Test
    fun `opt out discards buffered samples`() {
        val c = startCore(enabled())
        c.onForeground()
        waitUntil { delays.get() == 1 }
        tick(3)
        c.optOut(true)
        c.optOut(false)
        c.onBackground()
        settle()
        assertTrue(perfItems().isEmpty())
    }

    @Test
    fun `memory warning is emitted in the foreground only`() {
        val c = startCore(enabled())
        c.onForeground()
        app.onTrimMemory(10)
        app.onTrimMemory(20)
        settle()
        val warnings = seen.filter { it.name == "perf.memory_warning" }
        assertEquals(1, warnings.size)
        assertEquals(10, warnings.single().attributes["level"])
        c.onBackground()
        app.onTrimMemory(15)
        settle()
        assertEquals(1, seen.count { it.name == "perf.memory_warning" })
    }

    @Test
    fun `shutdown unregisters the memory callbacks`() {
        val c = startCore(enabled())
        c.onForeground()
        c.shutdown()
        app.onTrimMemory(10)
        Thread.sleep(100)
        assertTrue(seen.none { it.name == "perf.memory_warning" })
    }

    @Test
    fun `window registry follows the resumed activity`() {
        val c = startCore(enabled())
        val events = ArrayList<String>()
        val listener = object : ActivityTracker.WindowListener {
            override fun onActivityResumed(a: Activity) {
                events += "resumed"
            }

            override fun onActivityPaused(a: Activity) {
                events += "paused"
            }
        }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        c.windowRegistry.add(listener)
        assertEquals(listOf("resumed"), events) // an already resumed activity is replayed on add
        controller.pause()
        assertEquals(listOf("resumed", "paused"), events)
        c.windowRegistry.remove(listener)
        controller.resume()
        assertEquals(listOf("resumed", "paused"), events)
    }
}
