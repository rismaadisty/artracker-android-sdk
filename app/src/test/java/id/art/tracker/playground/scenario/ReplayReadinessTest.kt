package id.art.tracker.playground.scenario

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayReadinessTest {
    @Test
    fun warmupIsTwoFramePeriodsPlusHalfASecond() {
        assertEquals(2_500L, ReplayReadiness.warmupMs(1.0))
        assertEquals(4_500L, ReplayReadiness.warmupMs(0.5))
        assertEquals(1_000L, ReplayReadiness.warmupMs(4.0))
    }

    @Test
    fun fpsIsClampedLikeTheSdk() {
        assertEquals(700L, ReplayReadiness.warmupMs(100.0))
        assertEquals(4_500L, ReplayReadiness.warmupMs(0.1))
    }

    @Test
    fun nonFiniteFpsMeansTheSdkDefaultOfOne() {
        assertEquals(2_500L, ReplayReadiness.warmupMs(Double.NaN))
        assertEquals(2_500L, ReplayReadiness.warmupMs(Double.POSITIVE_INFINITY))
    }
}
