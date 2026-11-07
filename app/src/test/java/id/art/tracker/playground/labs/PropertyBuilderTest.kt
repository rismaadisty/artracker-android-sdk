package id.art.tracker.playground.labs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyBuilderTest {
    private fun ok(rows: List<PropertyRow>): Map<String, Any?> = (PropertyBuilder.build(rows) as BuildResult.Ok).map

    @Test
    fun numberRowParsesIntAndDouble() {
        val map = ok(listOf(PropertyRow("a", PropertyType.NUMBER, "42"), PropertyRow("b", PropertyType.NUMBER, "3.5")))
        assertEquals(42L, map["a"])
        assertEquals(3.5, map["b"])
    }

    @Test
    fun badNumberReportsTheRowIndex() {
        val result = PropertyBuilder.build(listOf(PropertyRow("a", PropertyType.STRING, "x"), PropertyRow("n", PropertyType.NUMBER, "x")))
        assertEquals(1, (result as BuildResult.Error).row)
    }

    @Test
    fun blankKeyIsAnError() {
        val result = PropertyBuilder.build(listOf(PropertyRow("  ", PropertyType.STRING, "x")))
        assertEquals(0, (result as BuildResult.Error).row)
    }

    @Test
    fun duplicateKeyIsAnErrorOnTheSecondOccurrence() {
        val result = PropertyBuilder.build(
            listOf(PropertyRow("k", PropertyType.STRING, "1"), PropertyRow("z", PropertyType.STRING, "2"), PropertyRow("k", PropertyType.STRING, "3")),
        )
        assertEquals(2, (result as BuildResult.Error).row)
    }

    @Test
    fun nullRowProducesANullValue() {
        val map = ok(listOf(PropertyRow("n", PropertyType.NULL, "ignored")))
        assertTrue(map.containsKey("n"))
        assertNull(map["n"])
    }

    @Test
    fun booleanAcceptsOnlyTrueAndFalse() {
        val map = ok(listOf(PropertyRow("t", PropertyType.BOOLEAN, "true"), PropertyRow("f", PropertyType.BOOLEAN, "FALSE")))
        assertEquals(true, map["t"])
        assertEquals(false, map["f"])
        for (bad in listOf("yes", "1", "")) {
            assertTrue(bad, PropertyBuilder.build(listOf(PropertyRow("b", PropertyType.BOOLEAN, bad))) is BuildResult.Error)
        }
    }

    @Test
    fun stringRowKeepsTheRawTextAndKeysAreTrimmed() {
        val map = ok(listOf(PropertyRow(" k ", PropertyType.STRING, " v ")))
        assertEquals(" v ", map["k"])
    }

    @Test
    fun emptyListIsAnEmptyMap() {
        assertEquals(emptyMap<String, Any?>(), ok(emptyList()))
    }
}
