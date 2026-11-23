package id.art.tracker.appstart

import id.art.tracker.AppStartConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupProfilerTest {
    private val processStart = 5_000L
    private var uptime = processStart + 300
    private var reads = 0
    private val stack = arrayOf(StackTraceElement("com.example.Main", "onCreate", "Main.kt", 12))

    private fun profiler(
        maxDurationMs: Long = 10_000,
        source: MainStackSource = MainStackSource { reads++; stack },
        sleep: (Long) -> Unit = { uptime += it },
        nanoTime: () -> Long = { 0L },
    ) = StartupProfiler(source, { uptime }, nanoTime, processStart, maxDurationMs, sleep)

    @Test
    fun samplesUntilStopped_withTRelativeToTheProcessStart() {
        lateinit var p: StartupProfiler
        var sleeps = 0
        p = profiler(sleep = { uptime += it; if (++sleeps == 5) p.stop() })
        p.runLoopForTest()

        val result = p.stop()!!
        assertEquals(listOf<Any?>(300L, 310L, 320L, 330L, 340L), result.buffer.samples().map { it["t"] })
        assertEquals(10L, result.intervalMs)
    }

    @Test
    fun stopsAtMaxDuration() {
        val p = profiler(maxDurationMs = 100)
        p.runLoopForTest()

        // t0 = 300 after the process start, so the loop is already past 100 ms: nothing is sampled
        assertNull(p.stop())

        uptime = processStart
        val q = profiler(maxDurationMs = 100)
        q.runLoopForTest()
        assertEquals(10, q.stop()!!.buffer.sampleCount)
    }

    @Test
    fun aThrowingOrEmptyStackRead_isSkipped() {
        // Review Focus 5: nothing escapes, the loop goes on, the bad samples are simply missing.
        uptime = processStart
        var calls = 0
        val source = MainStackSource {
            calls++
            when (calls) {
                2 -> error("boom")
                3 -> emptyArray()
                else -> stack
            }
        }
        val p = profiler(maxDurationMs = 100, source = source)
        p.runLoopForTest() // must not throw

        assertEquals(10, calls)
        assertEquals(calls - 2, p.stop()!!.buffer.sampleCount)
    }

    @Test
    fun discard_returnsNothing() {
        uptime = processStart
        val p = profiler(maxDurationMs = 100)
        p.runLoopForTest()
        p.discard()

        assertNull(p.stop())
    }

    @Test
    fun anExpensiveSampler_backsOffAndReportsTheFinalInterval() {
        uptime = processStart
        var clock = 0L
        // every read "costs" 2 ms on the nano clock
        val p = profiler(maxDurationMs = 500, source = MainStackSource { clock += 2_000_000; stack }, nanoTime = { clock })
        p.runLoopForTest()

        val result = p.stop()!!
        assertEquals(40L, result.intervalMs)
        assertEquals(2_000L, result.meanCostMicros)
    }

    @Test
    fun aFullBuffer_endsTheLoop() {
        uptime = processStart
        val p = profiler(maxDurationMs = 1_000_000)
        p.runLoopForTest()

        assertEquals(1000, p.stop()!!.buffer.sampleCount)
    }

    @Test
    fun theRealThread_samplesStopsAndEnds() {
        val p = StartupProfiler(
            source = MainStackSource { stack },
            uptime = { processStart + 1 },
            nanoTime = System::nanoTime,
            processStartUptimeMs = processStart,
        )
        p.start()
        assertTrue(samplerThreads().all { it.isDaemon })
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && samplerThreads().isNotEmpty() && p.sampleCountForTest() == 0) Thread.sleep(5)

        val result = p.stop()
        assertNotNull(result)
        assertTrue(result!!.buffer.sampleCount >= 1)
        assertTrue("the sampler thread has ended", samplerThreads().isEmpty())
    }

    @Test
    fun theRealThread_discardEndsItAndStopReturnsNothing() {
        val p = StartupProfiler({ stack }, { processStart + 1 }, System::nanoTime, processStart)
        p.start()
        p.discard()
        Thread.sleep(100)

        assertTrue(samplerThreads().isEmpty())
        assertNull(p.stop())
    }

    private fun samplerThreads() = Thread.getAllStackTraces().keys.filter { it.name == "artracker-startup-sampler" && it.isAlive }

    @Test
    fun profileDecision() {
        assertFalse(ProfileDecision.shouldProfile(AppStartConfig(profiling = false, profileSampleRate = 1.0)) { 0.0 })
        assertTrue(ProfileDecision.shouldProfile(AppStartConfig(profiling = true, profileSampleRate = 0.1)) { 0.05 })
        assertFalse(ProfileDecision.shouldProfile(AppStartConfig(profiling = true, profileSampleRate = 0.1)) { 0.2 })
        assertFalse(ProfileDecision.shouldProfile(AppStartConfig(enabled = false, profiling = true, profileSampleRate = 1.0)) { 0.0 })
        assertFalse("rate 0 never profiles", ProfileDecision.shouldProfile(AppStartConfig(profiling = true, profileSampleRate = 0.0)) { 0.0 })
        assertTrue("rate 1 always profiles", ProfileDecision.shouldProfile(AppStartConfig(profiling = true, profileSampleRate = 1.0)) { 0.999999 })
    }
}
