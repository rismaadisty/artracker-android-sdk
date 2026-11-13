package id.art.tracker.perf

import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.FakeDeviceProbe
import id.art.tracker.testutil.FakeFrameSource
import id.art.tracker.testutil.FakeNetworkProbe
import id.art.tracker.testutil.FakeProcessReader
import id.art.tracker.testutil.statLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfSamplerTest {
    private val clock = FakeClock(10_000)
    private var mono = 5_000L
    private val reader = FakeProcessReader(statLine(0, 0, threads = 34), "1000 2560 0 0 0 0 0")
    private val device = FakeDeviceProbe(cores = 1)
    private val frames = FakeFrameSource()
    private val network = FakeNetworkProbe()

    private fun sampler(withFrames: Boolean = true) =
        PerfSampler(reader, if (withFrames) frames else null, device, network, clock) { mono }

    private fun advance(ms: Long, ticks: Long) {
        clock.advance(ms)
        mono += ms
        reader.stat = statLine(ticks, 0, threads = 34)
    }

    @Test
    fun `first sample has no cpu and the second is computed from the tick delta`() {
        val s = sampler()
        val first = s.sample()
        assertNull(first.cpu)
        advance(1000, ticks = 25)
        assertEquals(25.0, s.sample().cpu!!, 0.0001)
    }

    @Test
    fun `timestamp comes from the core clock`() {
        assertEquals(10_000L, sampler().sample().t)
    }

    @Test
    fun `mem is resident pages times page size in MB`() {
        val s = sampler().sample()
        assertEquals(2560 * 4096 / 1_048_576.0, s.memMb!!, 0.0001)
    }

    @Test
    fun `threads come from stat`() {
        assertEquals(34, sampler().sample().threads)
    }

    @Test
    fun `battery and thermal come from the probe`() {
        device.battery = 0.8
        device.thermal = "fair"
        val s = sampler().sample()
        assertEquals(0.8, s.battery!!, 0.0)
        assertEquals("fair", s.thermal)
    }

    @Test
    fun `sample carries ram storage and net`() {
        device.memory = MemoryReading(2310, false)
        device.storageFree = 51_200
        network.net = "wifi"
        val s = sampler().sample()
        assertEquals(2310, s.ramFreeMb)
        assertEquals(51_200, s.storageFreeMb)
        assertEquals("wifi", s.net)
        val wire = s.toMap()
        assertEquals(2310, wire["ram_free_mb"])
        assertEquals(51_200, wire["storage_free_mb"])
        assertEquals("wifi", wire["net"])
    }

    @Test
    fun `low memory comes from the same reading and is not on the wire`() {
        device.memory = MemoryReading(100, true)
        val s = sampler().sample()
        assertEquals(100, s.ramFreeMb)
        assertEquals(true, s.lowMemory)
        assertFalse(s.toMap().keys.any { it.contains("low") })
    }

    @Test
    fun `no reading means no low memory flag`() {
        device.memory = null
        assertNull(sampler().sample().lowMemory)
    }

    @Test
    fun `sampling warms the cached device facts off the flushing thread`() {
        sampler().sample()
        assertTrue(device.storageTotalReads >= 1)
        assertTrue(device.ramReads >= 1)
    }

    @Test
    fun `null readings are omitted`() {
        device.memory = null
        device.storageFree = null
        network.net = null
        val s = sampler().sample()
        assertNull(s.ramFreeMb)
        assertNull(s.storageFreeMb)
        assertNull(s.net)
        val wire = s.toMap()
        assertFalse(wire.containsKey("ram_free_mb"))
        assertFalse(wire.containsKey("storage_free_mb"))
        assertFalse(wire.containsKey("net"))
        assertFalse(wire.containsKey("network"))
    }

    @Test
    fun `unreadable proc omits cpu mem threads`() {
        reader.stat = null
        reader.statm = null
        val s = sampler()
        val first = s.sample()
        assertEquals(10_000L, first.t)
        assertNull(first.cpu)
        assertNull(first.memMb)
        assertNull(first.threads)
        // The next read works again and still does not crash on a baseline that never existed.
        advance(1000, ticks = 25)
        reader.statm = "1000 2560 0 0 0 0 0"
        assertNull(s.sample().cpu)
        advance(1000, ticks = 50)
        assertEquals(25.0, s.sample().cpu!!, 0.0001)
    }

    @Test
    fun `garbage proc content is treated as unreadable`() {
        reader.stat = "garbage"
        reader.statm = "x"
        val s = sampler().sample()
        assertNull(s.cpu)
        assertNull(s.memMb)
        assertNull(s.threads)
    }

    @Test
    fun `without a frame source fps jank and frozen are absent`() {
        val s = sampler(withFrames = false).sample()
        assertNull(s.fps)
        assertNull(s.jank)
        assertNull(s.frozen)
    }

    @Test
    fun `frames over the interval give fps and counters`() {
        val s = sampler()
        s.sample() // first window: elapsed unknown
        frames.window = FrameWindow(58, 3, 1, 90f)
        advance(1000, ticks = 10)
        val sample = s.sample()
        assertEquals(58.0, sample.fps!!, 0.0001)
        assertEquals(3, sample.jank)
        assertEquals(1, sample.frozen)
        assertEquals(90f, s.refreshHz!!, 0f)
    }

    @Test
    fun `an idle screen has no fps`() {
        val s = sampler()
        s.sample()
        frames.window = FrameWindow(1, 0, 0, 60f)
        advance(1000, ticks = 10)
        val sample = s.sample()
        assertNull(sample.fps)
        assertEquals(0, sample.jank)
    }

    @Test
    fun `resetBaseline forgets the cpu baseline and drops stale frames`() {
        val s = sampler()
        s.sample()
        advance(1000, ticks = 25)
        frames.window = FrameWindow(500, 9, 9, 60f) // counted while the app was in the background
        s.resetBaseline()
        advance(60_000, ticks = 25_000)
        val after = s.sample()
        assertNull(after.cpu)
        assertNull(after.fps)
        assertEquals(0, after.jank)
    }

    @Test
    fun `refreshHz is null while no frame source exists`() {
        val s = sampler(withFrames = false)
        s.sample()
        assertNull(s.refreshHz)
    }

    @Test
    fun `toMap omits null keys and uses wire names`() {
        val full = PerfSample(1L, 12.34, 56.78, 58.26, 2, 1, 30, 0.5, "nominal").toMap()
        assertEquals(
            listOf("t", "cpu", "mem_mb", "fps", "jank", "frozen", "threads", "battery", "thermal"),
            full.keys.toList(),
        )
        assertEquals(12.3, full["cpu"])
        assertEquals(56.8, full["mem_mb"])
        assertEquals(58.3, full["fps"])
        val extra = PerfSample(1L, null, null, null, null, null, null, null, null, 900, 12_345, "wifi").toMap()
        assertEquals(
            mapOf<String, Any?>("t" to 1L, "ram_free_mb" to 900, "storage_free_mb" to 12_345, "net" to "wifi"),
            extra,
        )
        val sparse = PerfSample(1L, null, null, null, null, null, null, null, null).toMap()
        assertEquals(mapOf<String, Any?>("t" to 1L), sparse)
        assertFalse(sparse.containsKey("memMb"))
        assertTrue(full.containsKey("mem_mb"))
        assertNotNull(full["thermal"])
    }

    @Test
    fun `start and stop drive the frame source`() {
        val s = sampler()
        s.start()
        s.stop()
        assertEquals(1, frames.started)
        assertEquals(1, frames.stopped)
        sampler(withFrames = false).also { it.start(); it.stop() } // no frame source: nothing to drive
    }
}
