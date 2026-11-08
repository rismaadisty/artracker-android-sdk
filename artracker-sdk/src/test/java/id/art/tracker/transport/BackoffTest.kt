package id.art.tracker.transport

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    /** Random whose nextDouble() always returns just under 1.0, i.e. the largest possible jitter. */
    private class MaxRandom : Random() {
        override fun nextDouble(): Double = Math.nextDown(1.0)
        override fun next(bits: Int): Int = (1 shl bits) - 1
    }

    @Test
    fun neverExceedsMax() {
        val backoff = Backoff(random = Random(42))
        repeat(200) { _ ->
            for (attempt in 0..30) {
                val d = backoff.delayFor(attempt)
                assertTrue("attempt=$attempt d=$d", d in 0..300_000)
            }
        }
    }

    @Test
    fun attempt0_withinBase() {
        val backoff = Backoff(random = Random(7))
        repeat(500) {
            val d = backoff.delayFor(0)
            assertTrue("d=$d", d in 0..2_000)
        }
    }

    @Test
    fun ceilingGrowsExponentiallyThenCaps() {
        val backoff = Backoff(random = MaxRandom())
        assertEquals(2_000L, backoff.delayFor(0))
        assertEquals(4_000L, backoff.delayFor(1))
        assertEquals(8_000L, backoff.delayFor(2))
        assertEquals(256_000L, backoff.delayFor(7))
        assertEquals(300_000L, backoff.delayFor(8))
    }

    @Test
    fun hugeAttempt_doesNotOverflow() {
        val backoff = Backoff(random = MaxRandom())
        assertEquals(300_000L, backoff.delayFor(100))
        assertEquals(300_000L, backoff.delayFor(Int.MAX_VALUE))
    }

    @Test
    fun negativeAttempt_treatedAsZero() {
        val backoff = Backoff(random = MaxRandom())
        assertEquals(2_000L, backoff.delayFor(-5))
    }

    @Test
    fun reset_isSafeToCall() {
        val backoff = Backoff(random = Random(1))
        backoff.delayFor(3)
        backoff.reset()
        assertTrue(backoff.delayFor(0) in 0..2_000)
    }
}
