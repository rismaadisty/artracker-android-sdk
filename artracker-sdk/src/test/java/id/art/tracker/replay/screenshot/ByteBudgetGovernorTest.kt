package id.art.tracker.replay.screenshot

import id.art.tracker.ReplayQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteBudgetGovernorTest {
    private val mb = 1024L * 1024

    private fun governor(baseFps: Double = 2.0, quality: ReplayQuality = ReplayQuality.STANDARD) =
        ByteBudgetGovernor(baseFps, quality)

    @Test
    fun underBudget_changesNothing() {
        val g = governor()
        g.record(0, 1 * mb)
        g.record(30_000, 2 * mb)
        assertNull(g.fpsCap)
        assertEquals(ReplayQuality.STANDARD, g.quality)
    }

    @Test
    fun overFiveMegabytesInAMinute_halvesTheFps() {
        val g = governor(baseFps = 2.0)
        g.record(0, 6 * mb)
        assertEquals(1.0, g.fpsCap!!, 0.0)
    }

    @Test
    fun keepsHalvingWhileOverBudget_butNotMoreThanOncePerTenSeconds_andStopsAtHalfAFps() {
        val g = governor(baseFps = 4.0)
        g.record(0, 6 * mb)
        assertEquals(2.0, g.fpsCap!!, 0.0)
        g.record(1_000, 100_000)
        assertEquals(2.0, g.fpsCap!!, 0.0) // cooldown
        g.record(10_000, 100_000)
        assertEquals(1.0, g.fpsCap!!, 0.0)
        g.record(20_000, 100_000)
        assertEquals(0.5, g.fpsCap!!, 0.0)
        g.record(30_000, 100_000)
        assertEquals(0.5, g.fpsCap!!, 0.0)
    }

    @Test
    fun recovers_onlyOnceTheWindowIsBackUnder80Percent() {
        val g = governor(baseFps = 2.0)
        g.record(0, 6 * mb)
        assertEquals(1.0, g.fpsCap!!, 0.0)
        // 62 s later the 6 MB aged out of the window; 4.5 MB arrives: still above 80 % (4 MB), under 5 MB
        g.record(62_000, 4 * mb + mb / 2)
        assertEquals(1.0, g.fpsCap!!, 0.0)
        g.record(125_000, 1 * mb)
        assertNull(g.fpsCap)
    }

    @Test
    fun persistentOverrunAtTheFloor_stepsQualityDownOnePreset() {
        val g = governor(baseFps = 0.5, quality = ReplayQuality.HIGH)
        var t = 0L
        g.record(t, 6 * mb) // cap -> floor (0.5)
        assertEquals(ReplayQuality.HIGH, g.quality)
        t += 10_000; g.record(t, 100_000) // overrun at the floor, first time
        assertEquals(ReplayQuality.HIGH, g.quality)
        t += 10_000; g.record(t, 100_000) // second: persistent
        assertEquals(ReplayQuality.STANDARD, g.quality)
        t += 10_000; g.record(t, 100_000)
        t += 10_000; g.record(t, 100_000)
        assertEquals(ReplayQuality.LOW, g.quality)
        t += 10_000; g.record(t, 100_000)
        t += 10_000; g.record(t, 100_000)
        assertEquals(ReplayQuality.LOW, g.quality) // nothing below LOW
    }

    @Test
    fun qualityNeverGoesBackUp_afterRecovery() {
        val g = governor(baseFps = 0.5, quality = ReplayQuality.STANDARD)
        var t = 0L
        repeat(3) { g.record(t, 6 * mb); t += 10_000 }
        assertEquals(ReplayQuality.LOW, g.quality)
        g.record(t + 200_000, 1000)
        assertNull(g.fpsCap)
        assertEquals(ReplayQuality.LOW, g.quality)
    }
}
