package id.art.tracker.replay

import id.art.tracker.core.UuidV7
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {
    @Test
    fun deterministic() {
        val id = UuidV7.generate(1_700_000_000_000L)
        val first = Sampling.isSampled(id, 0.5)
        repeat(100) { assertEquals(first, Sampling.isSampled(id, 0.5)) }
    }

    @Test
    fun rate0_never_rate1_always() {
        val random = Random(7)
        repeat(1_000) {
            val id = UuidV7.generate(1_700_000_000_000L + it, random)
            assertFalse(Sampling.isSampled(id, 0.0))
            assertFalse(Sampling.isSampled(id, -1.0))
            assertFalse(Sampling.isSampled(id, Double.NaN))
            assertTrue(Sampling.isSampled(id, 1.0))
            assertTrue(Sampling.isSampled(id, 2.0))
        }
    }

    @Test
    fun rateApprox() {
        val random = Random(42)
        val n = 10_000
        val sampled = (0 until n).count { Sampling.isSampled(UuidV7.generate(1_700_000_000_000L + it * 37L, random), 0.1) }
        val rate = sampled.toDouble() / n
        assertTrue("rate=$rate", rate in 0.08..0.12)
    }

    /** Vectors computed with the web SDK's `hash32` (artracker-web-sdk/src/core/sampling.ts) under node. */
    @Test
    fun matchesWebSdk() {
        val vectors = mapOf(
            "ses_test1" to 1_623_636_710L,
            "0197a5b0-7c3e-7a4b-9d2f-1e8f6c5a4b3d" to 3_933_561_209L,
            "abc" to 440_920_331L,
        )
        for ((id, hash) in vectors) {
            assertEquals(id, hash, Sampling.hash32(id))
            val fraction = hash / 4_294_967_296.0
            assertEquals(fraction < 0.5, Sampling.isSampled(id, 0.5))
        }
        // abc -> 0.10266: just above 0.1, just below 0.11 (boundary behaviour identical to the web SDK).
        assertFalse(Sampling.isSampled("abc", 0.1))
        assertTrue(Sampling.isSampled("abc", 0.11))
        assertTrue(Sampling.isSampled("0197a5b0-7c3e-7a4b-9d2f-1e8f6c5a4b3d", 0.92))
        assertFalse(Sampling.isSampled("0197a5b0-7c3e-7a4b-9d2f-1e8f6c5a4b3d", 0.91))
    }

    @Test
    fun usesUtf16CodeUnits() {
        // A non-BMP character is two code units, as with JavaScript's charCodeAt.
        var h = 0x811c9dc5.toInt()
        for (unit in intArrayOf(0xD83D, 0xDE00)) {
            h = h xor unit
            h *= 0x01000193
        }
        assertEquals(h.toLong() and 0xFFFFFFFFL, Sampling.hash32("😀"))
    }
}
