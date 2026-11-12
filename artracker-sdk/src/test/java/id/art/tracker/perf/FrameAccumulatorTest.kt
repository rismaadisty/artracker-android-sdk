package id.art.tracker.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameAccumulatorTest {
    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun `counts frames jank and frozen at 60 Hz`() {
        val acc = FrameAccumulator()
        acc.onFrame(ms(10), 60f)
        acc.onFrame(ms(40), 60f)
        acc.onFrame(ms(800), 60f)
        assertEquals(FrameWindow(3, jank = 2, frozen = 1, refreshHz = 60f), acc.drain())
    }

    @Test
    fun `drain resets the counters`() {
        val acc = FrameAccumulator()
        acc.onFrame(ms(10), 60f)
        acc.drain()
        assertEquals(0, acc.drain().frames)
    }

    @Test
    fun `jank threshold follows the refresh rate`() {
        val acc = FrameAccumulator()
        acc.onFrame(ms(20), 120f) // > 2 x 8.33 ms
        assertEquals(1, acc.drain().jank)
        acc.onFrame(ms(20), 60f) // < 2 x 16.67 ms
        assertEquals(0, acc.drain().jank)
    }

    @Test
    fun `refresh rate defaults to 60 and keeps the last seen value across drains`() {
        val acc = FrameAccumulator()
        assertEquals(60f, acc.drain().refreshHz, 0f)
        acc.onFrame(ms(5), 90f)
        acc.drain()
        assertEquals(90f, acc.drain().refreshHz, 0f)
    }

    @Test
    fun `a frame of exactly 700 ms is not frozen`() {
        val acc = FrameAccumulator()
        acc.onFrame(ms(700), 60f)
        assertEquals(0, acc.drain().frozen)
    }

    @Test
    fun `drain with one frame omits fps`() {
        assertNull(FrameWindow(1, 0, 0, 60f).fps(1000))
        assertEquals(58.0, FrameWindow(58, 0, 0, 60f).fps(1000)!!, 0.0001)
        assertNull(FrameWindow(58, 0, 0, 60f).fps(0))
        assertNull(FrameWindow(0, 0, 0, 60f).fps(1000))
    }
}
