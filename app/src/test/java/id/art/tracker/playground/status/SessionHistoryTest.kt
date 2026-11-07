package id.art.tracker.playground.status

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionHistoryTest {
    @Test
    fun recordsOnlyChangesAndIgnoresNull() {
        val h = SessionHistory()
        listOf("a", "a", "b", null, "b", "c").forEach(h::observe)
        assertEquals(listOf("a", "b", "c"), h.ids)
    }

    @Test
    fun keepsTheNewestWhenCapped() {
        val h = SessionHistory(max = 3)
        listOf("a", "b", "c", "d", "e").forEach(h::observe)
        assertEquals(listOf("c", "d", "e"), h.ids)
    }
}
