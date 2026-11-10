package id.art.tracker.replay.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFilterTest {
    private fun pixels(fill: Int = 0x123456) = IntArray(64 * 64) { fill }

    @Test
    fun crc_isStableForEqualPixels_andDiffersWhenOnePixelChanges() {
        val a = pixels()
        val b = pixels().also { it[1234] = 0xFFFFFF }
        assertTrue(DuplicateFilter.crc(a) == DuplicateFilter.crc(pixels()))
        assertNotEquals(DuplicateFilter.crc(a), DuplicateFilter.crc(b))
    }

    @Test
    fun theFirstFrameIsNeverADuplicate_theSameFrameAgainIs() {
        val f = DuplicateFilter()
        assertFalse(f.isDuplicate(DuplicateFilter.crc(pixels())))
        assertTrue(f.isDuplicate(DuplicateFilter.crc(pixels())))
        assertFalse(f.isDuplicate(DuplicateFilter.crc(pixels(0x654321))))
        assertFalse(f.isDuplicate(DuplicateFilter.crc(pixels())))
    }

    @Test
    fun reset_makesTheNextFrameNew() {
        val f = DuplicateFilter()
        f.isDuplicate(DuplicateFilter.crc(pixels()))
        f.reset()
        assertFalse(f.isDuplicate(DuplicateFilter.crc(pixels())))
    }
}
