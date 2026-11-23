package id.art.tracker.appstart

import org.junit.Assert.assertEquals
import org.junit.Test

class IntervalPolicyTest {
    @Test
    fun cheapSamples_keep10ms() {
        val policy = IntervalPolicy()
        repeat(100) { policy.record(200_000) }
        assertEquals(10L, policy.intervalMs)
    }

    @Test
    fun expensiveSamples_doubleUpTo40() {
        val policy = IntervalPolicy()
        repeat(IntervalPolicy.WINDOW) { policy.record(2_000_000) }
        assertEquals(20L, policy.intervalMs)
        repeat(IntervalPolicy.WINDOW) { policy.record(2_000_000) }
        assertEquals(40L, policy.intervalMs)
        repeat(IntervalPolicy.WINDOW * 3) { policy.record(2_000_000) }
        assertEquals("capped", 40L, policy.intervalMs)
    }

    @Test
    fun aSingleSlowSample_amongCheapOnes_doesNotBackOff() {
        // The first stack read of a process is slow (class loading); it must not slow the whole profile down.
        val policy = IntervalPolicy()
        repeat(IntervalPolicy.WINDOW - 1) { policy.record(200_000) }
        policy.record(4_000_000)
        assertEquals(10L, policy.intervalMs)
    }

    @Test
    fun theMeanIsResetAfterABackOff() {
        val policy = IntervalPolicy()
        repeat(IntervalPolicy.WINDOW) { policy.record(2_000_000) } // -> 20
        repeat(IntervalPolicy.WINDOW) { policy.record(100_000) } // a cheap window: stays 20
        assertEquals(20L, policy.intervalMs)
    }

    @Test
    fun aMeanExactlyAtTheThreshold_doesNotBackOff() {
        val policy = IntervalPolicy()
        repeat(IntervalPolicy.WINDOW) { policy.record(1_000_000) }
        assertEquals(10L, policy.intervalMs)
    }
}
