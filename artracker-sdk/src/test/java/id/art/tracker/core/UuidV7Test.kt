package id.art.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidV7Test {
    @Test
    fun versionAndVariant() {
        val id = UuidV7.generate()
        assertEquals(36, id.length)
        assertEquals('7', id[14])
        assertTrue("89ab".indexOf(id[19]) >= 0)
    }

    @Test
    fun timestampPrefix() {
        assertTrue(UuidV7.generate(nowMs = 0x0123456789ABL).startsWith("01234567-89ab-7"))
    }

    @Test
    fun sortableAcrossMillis() {
        assertTrue(UuidV7.generate(1000) < UuidV7.generate(1001))
    }
}
