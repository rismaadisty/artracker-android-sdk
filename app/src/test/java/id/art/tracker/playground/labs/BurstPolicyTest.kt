package id.art.tracker.playground.labs

import org.junit.Assert.assertEquals
import org.junit.Test

class BurstPolicyTest {
    @Test
    fun anyLoadOnARemoteEndpointIsBlocked() {
        assertEquals(BurstDecision.BLOCK, BurstPolicy.decide(5_000, remote = true))
        assertEquals(BurstDecision.BLOCK, BurstPolicy.decide(50, remote = true))
        assertEquals(BurstDecision.BLOCK, BurstPolicy.decide(1, remote = true))
    }

    @Test
    fun localBurstsAreAllowedUpToTheConfirmThreshold() {
        assertEquals(BurstDecision.ALLOW, BurstPolicy.decide(1_000, remote = false))
        assertEquals(BurstDecision.CONFIRM, BurstPolicy.decide(1_001, remote = false))
        assertEquals(BurstDecision.CONFIRM, BurstPolicy.decide(10_000, remote = false))
    }

    @Test
    fun clampKeepsTheCountInRange() {
        assertEquals(0, BurstPolicy.clamp(-5))
        assertEquals(500, BurstPolicy.clamp(500))
        assertEquals(10_000, BurstPolicy.clamp(20_000))
    }
}
