package id.art.tracker.core

import org.junit.Assert.assertEquals
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
}
