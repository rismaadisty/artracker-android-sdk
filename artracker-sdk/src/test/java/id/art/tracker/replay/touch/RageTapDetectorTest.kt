package id.art.tracker.replay.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Three or more taps within a second, each within 24 dp of the first of the burst. Pure logic, no Android. */
class RageTapDetectorTest {
    private val d = RageTapDetector()

    @Test
    fun threeDownsInOneSecondAtOneSpot_isRage() {
        assertFalse(d.onDown(0, 100, 100))
        assertFalse(d.onDown(300, 102, 99))
        assertTrue(d.onDown(600, 101, 101))
    }

    @Test
    fun fourthDownOfTheSameBurst_staysRage() {
        d.onDown(0, 100, 100)
        d.onDown(300, 100, 100)
        assertTrue(d.onDown(600, 100, 100))
        assertTrue(d.onDown(700, 100, 100))
    }

    @Test
    fun thirdDownAtExactlyTheWindowEdge_isRage() {
        assertFalse(d.onDown(0, 100, 100))
        assertFalse(d.onDown(500, 100, 100))
        assertTrue(d.onDown(1000, 100, 100))
    }

    @Test
    fun thirdDownOneMsLate_isNotRage() {
        d.onDown(0, 100, 100)
        d.onDown(500, 100, 100)
        assertFalse(d.onDown(1001, 100, 100))
        // The late tap started a burst of its own: two more taps make three.
        assertFalse(d.onDown(1002, 100, 100))
        assertTrue(d.onDown(1003, 100, 100))
    }

    @Test
    fun tapsExactlyTwentyFourDpAway_areRage() {
        assertFalse(d.onDown(0, 100, 100))
        assertFalse(d.onDown(100, 124, 100))
        assertTrue(d.onDown(200, 100, 124))
    }

    @Test
    fun tapsTwentyFiveDpApart_areNotRage() {
        assertFalse(d.onDown(0, 100, 100))
        assertFalse(d.onDown(100, 125, 100))
        assertFalse(d.onDown(200, 100, 125))
    }

    @Test
    fun radiusIsMeasuredFromTheFirstDown_notThePrevious() {
        assertFalse(d.onDown(0, 100, 100))
        assertFalse(d.onDown(100, 120, 100))
        assertFalse("40 dp from the first tap, only 20 from the previous one", d.onDown(200, 140, 100))
    }

    @Test
    fun aFarTapStartsANewBurst() {
        val results = listOf(
            d.onDown(0, 0, 0), d.onDown(100, 0, 0),
            d.onDown(200, 500, 500), d.onDown(300, 500, 500), d.onDown(400, 500, 500),
        )
        assertEquals(listOf(false, false, false, false, true), results)
    }

    @Test
    fun clockGoingBackwards_startsANewBurst() {
        assertFalse(d.onDown(1000, 100, 100))
        assertFalse(d.onDown(1200, 100, 100))
        assertFalse("earlier than the first tap of the burst: a new burst starts here", d.onDown(900, 100, 100))
        assertFalse(d.onDown(1000, 100, 100))
        assertTrue(d.onDown(1100, 100, 100))
    }

    @Test
    fun reset_forgetsTheBurst() {
        d.onDown(0, 100, 100)
        d.onDown(100, 100, 100)
        d.reset()
        assertFalse(d.onDown(200, 100, 100))
    }

    @Test
    fun hugeCoordinates_doNotOverflow() {
        assertFalse(d.onDown(0, Int.MAX_VALUE, 0))
        assertFalse(d.onDown(10, Int.MIN_VALUE, 0))
        assertFalse(d.onDown(20, Int.MIN_VALUE, 0))
    }
}
