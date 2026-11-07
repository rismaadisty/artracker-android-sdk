package id.art.tracker.playground.labs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonasTest {
    @Test
    fun threeDistinctNonBlankPersonas() {
        assertEquals(3, Personas.all.size)
        assertEquals(3, Personas.all.map { it.userId }.toSet().size)
        assertTrue(Personas.all.all { it.userId.isNotBlank() && it.label.isNotBlank() })
    }
}
