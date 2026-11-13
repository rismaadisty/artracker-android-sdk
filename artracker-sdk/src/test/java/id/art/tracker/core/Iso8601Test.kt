package id.art.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Iso8601Test {
    @Test
    fun epochZero() {
        assertEquals("1970-01-01T00:00:00.000Z", Iso8601.format(0L))
    }

    @Test
    fun millisPreserved() {
        assertEquals("2025-11-05T08:33:18.050Z", Iso8601.format(1762331598050L))
    }

    @Test
    fun parseRoundTripsFormat() {
        assertEquals(1_700_000_000_123L, Iso8601.parse(Iso8601.format(1_700_000_000_123L)))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(Iso8601.parse("yesterday"))
        assertNull(Iso8601.parse(""))
        assertNull(Iso8601.parse("2025-11-05T08:33:18.050Zjunk"))
    }
}
