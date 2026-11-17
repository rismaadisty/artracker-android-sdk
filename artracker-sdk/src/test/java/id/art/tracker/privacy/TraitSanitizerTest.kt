package id.art.tracker.privacy

import java.util.Date
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraitSanitizerTest {
    @Test
    fun keys_cappedAt50_sortedOrder() {
        val traits = (0 until 60).associate { "k%02d".format(it) to it }
        val clean = TraitSanitizer.sanitize(traits)
        assertEquals(50, clean.size)
        assertEquals((0 until 50).map { "k%02d".format(it) }, clean.keys.toList())
    }

    @Test
    fun keys_sortedEvenWhenInputIsNot() {
        val clean = TraitSanitizer.sanitize(linkedMapOf("b" to 1, "a" to 2))
        assertEquals(listOf("a", "b"), clean.keys.toList())
    }

    @Test
    fun longKey_dropped_emptyKey_dropped() {
        val clean = TraitSanitizer.sanitize(
            mapOf("k".repeat(65) to 1, "" to 2, "k".repeat(64) to 3, "ok" to 4),
        )
        assertEquals(setOf("k".repeat(64), "ok"), clean.keys)
    }

    @Test
    fun longString_truncatedTo256() {
        val clean = TraitSanitizer.sanitize(mapOf("s" to "x".repeat(300), "short" to "y"))
        assertEquals("x".repeat(256), clean["s"])
        assertEquals("y", clean["short"])
    }

    @Test
    fun nonScalars_dropped() {
        val clean = TraitSanitizer.sanitize(
            mapOf(
                "date" to Date(),
                "enum" to TimeUnit.SECONDS,
                "list" to listOf(1),
                "map" to mapOf("a" to 1),
                "array" to arrayOf(1),
                "int" to 1,
                "double" to 1.5,
                "bool" to true,
                "str" to "x",
                "nul" to null,
            ),
        )
        assertEquals(setOf("int", "double", "bool", "str", "nul"), clean.keys)
        assertEquals(1, clean["int"])
        assertEquals(1.5, clean["double"])
        assertEquals(true, clean["bool"])
        assertEquals("x", clean["str"])
        assertTrue(clean.containsKey("nul"))
        assertNull(clean["nul"])
        assertFalse(clean.containsKey("date"))
    }

    @Test
    fun emptyInput_givesEmptyMap() {
        assertTrue(TraitSanitizer.sanitize(emptyMap()).isEmpty())
    }
}
