package id.art.tracker.perf

import id.art.tracker.ItemType
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ItemFactory
import id.art.tracker.core.Json
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.FakeDeviceProbe
import id.art.tracker.testutil.FakeFrameSource
import id.art.tracker.testutil.FakeProcessReader
import id.art.tracker.testutil.StrictJson
import id.art.tracker.testutil.statLine
import id.art.tracker.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfMonitorTest {
    private val clock = FakeClock(1_000)
    private var mono = 0L
    private val reader = FakeProcessReader(statLine(0, 0, threads = 20), "100 256 0 0 0 0 0")
    private val device = FakeDeviceProbe(ram = 4096, cores = 4, battery = 0.5, thermal = "nominal")
    private val frames = FakeFrameSource()
    private var session: String? = "s1"
    private val emitted = ArrayList<Pair<String, TelemetryItem>>()
    private var ticks = 0L

    private fun monitor(withFrames: Boolean = true, flushEvery: Int = PerfMonitor.FLUSH_EVERY): PerfMonitor {
        val sampler = PerfSampler(reader, if (withFrames) frames else null, device, clock) { mono }
        return PerfMonitor(
            sampler, device, intervalMs = 1_000, sessionId = { session }, flushEvery = flushEvery,
            emit = { sid, build -> emitted += sid to build(ItemFactory(clock, { sid }, { null })) },
        )
    }

    private fun PerfMonitor.tickOnce() {
        clock.advance(1_000)
        mono += 1_000
        ticks += 25
        reader.stat = statLine(ticks, 0, threads = 20)
        tick()
    }

    private fun perfItems() = emitted.filter { it.second.type == ItemType.PERF }

    private fun samplesOf(item: TelemetryItem) = (item.attributes["samples"] as List<*>)

    @Test
    fun `ten ticks emit exactly one item with ten samples`() {
        val m = monitor()
        repeat(10) { m.tickOnce() }
        val items = perfItems()
        assertEquals(1, items.size)
        assertEquals(10, samplesOf(items[0].second).size)
        assertEquals("perf.samples", items[0].second.name)
    }

    @Test
    fun `nine ticks emit nothing and flush emits nine`() {
        val m = monitor()
        repeat(9) { m.tickOnce() }
        assertTrue(emitted.isEmpty())
        m.flush()
        assertEquals(9, samplesOf(perfItems().single().second).size)
        m.flush() // buffer is empty now: no-op
        assertEquals(1, perfItems().size)
    }

    @Test
    fun `item timestamp is the first sample time`() {
        val m = monitor()
        repeat(10) { m.tickOnce() }
        assertEquals(2_000L, perfItems().single().second.timestampMs)
    }

    @Test
    fun `tick is skipped without a session`() {
        val m = monitor()
        session = null
        repeat(10) { m.tickOnce() }
        m.flush()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `session change flushes old samples under old id`() {
        val m = monitor()
        repeat(3) { m.tickOnce() }
        session = "s2"
        m.tickOnce()
        val items = perfItems()
        assertEquals(1, items.size)
        assertEquals("s1", items[0].first)
        assertEquals("s1", items[0].second.sessionId)
        assertEquals(3, samplesOf(items[0].second).size)
        m.flush()
        val second = perfItems()[1]
        assertEquals("s2", second.first)
        assertEquals(1, samplesOf(second.second).size)
    }

    @Test
    fun `thermal change emits one event`() {
        val m = monitor()
        m.tickOnce()
        device.thermal = "serious"
        m.tickOnce()
        m.tickOnce()
        val events = emitted.filter { it.second.name == "perf.thermal_change" }
        assertEquals(1, events.size)
        assertEquals(ItemType.EVENT, events[0].second.type)
        assertEquals(mapOf("from" to "nominal", "to" to "serious"), events[0].second.attributes)
        assertEquals("s1", events[0].first)
    }

    @Test
    fun `missing thermal does not emit an event or forget the last state`() {
        val m = monitor()
        m.tickOnce()
        device.thermal = null
        m.tickOnce()
        device.thermal = "nominal"
        m.tickOnce()
        assertTrue(emitted.none { it.second.name == "perf.thermal_change" })
    }

    @Test
    fun `an item never holds more than 60 samples`() {
        val m = monitor(flushEvery = 1_000)
        repeat(130) { m.tickOnce() }
        m.flush()
        assertEquals(listOf(60, 60, 10), perfItems().map { samplesOf(it.second).size })
    }

    @Test
    fun `refresh_hz is omitted when frames are off`() {
        val m = monitor(withFrames = false)
        m.tickOnce()
        m.flush()
        @Suppress("UNCHECKED_CAST")
        val dev = perfItems().single().second.attributes["device"] as Map<String, Any?>
        assertNull(dev["refresh_hz"])
        assertEquals(4, dev["cores"])
        assertEquals(4096, dev["ram_mb"])
        assertEquals("14", dev["os_version"])
        assertEquals(128_000, dev["storage_mb"])
    }

    @Test
    fun `onForeground resets the baseline`() {
        val m = monitor()
        m.tickOnce()
        m.tickOnce()
        m.onForeground()
        mono += 300_000
        ticks += 100_000
        reader.stat = statLine(ticks, 0, threads = 20)
        clock.advance(300_000)
        m.tick()
        m.flush()
        val last = samplesOf(perfItems().single().second).last() as Map<*, *>
        assertNull(last["cpu"])
    }

    @Test
    fun `contract golden`() {
        device.ram = 3072
        device.cores = 8
        device.battery = 0.5
        device.thermal = "nominal"
        frames.window = FrameWindow(0, 0, 0, 120f)
        val m = monitor()
        // First tick only fills the baseline; frames of the following ticks are fixed.
        reader.statm = "100 512 0 0 0 0 0"
        m.tickOnce()
        repeat(9) {
            frames.window = FrameWindow(58, 2, 1, 120f)
            reader.statm = "100 512 0 0 0 0 0"
            clock.advance(1_000)
            mono += 1_000
            ticks += 100
            reader.stat = statLine(ticks, 0, threads = 20)
            m.tick()
        }
        val item = perfItems().single().second
        assertNull(StrictJson.validate(item.toJson()))
        val expected = "{\"v\":1,\"platform\":\"android\",\"interval_ms\":1000," +
            "\"device\":{\"os_version\":\"14\",\"ram_mb\":3072,\"storage_mb\":128000,\"cores\":8,\"refresh_hz\":120}," +
            "\"samples\":[" +
            "{\"t\":2000,\"mem_mb\":2.0,\"jank\":0,\"frozen\":0,\"threads\":20,\"battery\":0.5,\"thermal\":\"nominal\"," +
            "\"ram_free_mb\":1500,\"storage_free_mb\":64000,\"network\":\"wifi\"}," +
            (0 until 9).joinToString(",") { i ->
                "{\"t\":${3000 + i * 1000},\"cpu\":12.5,\"mem_mb\":2.0,\"fps\":58.0,\"jank\":2,\"frozen\":1," +
                    "\"threads\":20,\"battery\":0.5,\"thermal\":\"nominal\"," +
                    "\"ram_free_mb\":1500,\"storage_free_mb\":64000,\"network\":\"wifi\"}"
            } +
            "]}"
        assertEquals(expected, Json.encode(item.attributes))
    }

    @Test
    fun `onBackground flushes the buffer and stops the frame source`() {
        val m = monitor()
        m.onForeground()
        repeat(4) { m.tickOnce() }
        m.onBackground()
        assertEquals(4, samplesOf(perfItems().single().second).size)
        assertEquals(1, frames.started)
        assertEquals(1, frames.stopped)
    }

    @Test
    fun `discard drops buffered samples`() {
        val m = monitor()
        repeat(4) { m.tickOnce() }
        m.discard()
        m.flush()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `a tick racing background or opt-out is ignored until foreground`() {
        val m = monitor()
        m.onForeground()
        m.tickOnce()
        m.onBackground() // flushes 1 sample
        m.tickOnce() // a tick that was already past the loop's checks
        m.flush()
        assertEquals(1, perfItems().sumOf { samplesOf(it.second).size })
        m.onForeground()
        m.tickOnce()
        m.discard() // opt-out
        m.tickOnce()
        m.flush()
        assertEquals(1, perfItems().sumOf { samplesOf(it.second).size })
        m.onForeground() // opted back in
        m.tickOnce()
        m.flush()
        assertEquals(2, perfItems().sumOf { samplesOf(it.second).size })
    }
}
