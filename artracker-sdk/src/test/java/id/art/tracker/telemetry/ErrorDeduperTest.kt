package id.art.tracker.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorDeduperTest {
    @Test
    fun eleventhIdentical_isSuppressed() {
        val d = ErrorDeduper()
        repeat(10) { assertTrue("report #${it + 1}", d.shouldReport("s1", "fp")) }
        assertFalse(d.shouldReport("s1", "fp"))
        assertFalse(d.shouldReport("s1", "fp"))
    }

    @Test
    fun otherFingerprint_isCountedSeparately() {
        val d = ErrorDeduper(maxPerFingerprint = 2)
        assertTrue(d.shouldReport("s1", "a"))
        assertTrue(d.shouldReport("s1", "a"))
        assertFalse(d.shouldReport("s1", "a"))
        assertTrue(d.shouldReport("s1", "b"))
    }

    @Test
    fun newSession_startsFromZero() {
        val d = ErrorDeduper(maxPerFingerprint = 1)
        assertTrue(d.shouldReport("s1", "a"))
        assertFalse(d.shouldReport("s1", "a"))
        assertTrue(d.shouldReport("s2", "a"))
        assertFalse(d.shouldReport("s2", "a"))
    }

    @Test
    fun manyDistinctFingerprints_stayBounded_andRecentOnesStillDeduped() {
        val d = ErrorDeduper(maxPerFingerprint = 1)
        for (i in 0 until 10_000) d.shouldReport("s", "fp$i")
        assertFalse(d.shouldReport("s", "fp9999"))
    }
}
