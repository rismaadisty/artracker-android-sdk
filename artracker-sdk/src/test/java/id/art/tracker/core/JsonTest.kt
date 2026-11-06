package id.art.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test
    fun escapesControlAndQuotes() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.encode("a\"b\\c\n\u0001"))
    }

    @Test
    fun nanAndInfinity_becomeNull() {
        assertEquals("[null,null]", Json.encode(listOf(Double.NaN, Float.POSITIVE_INFINITY)))
    }

    @Test
    fun otherNumberTypes_areWrittenAsNumbersOnlyWhenTheirTextIsJson() {
        fun number(text: String) = object : Number() {
            override fun toByte(): Byte = 0
            override fun toDouble(): Double = 0.0
            override fun toFloat(): Float = 0f
            override fun toInt(): Int = 0
            override fun toLong(): Long = 0
            override fun toShort(): Short = 0
            override fun toString(): String = text
        }
        assertEquals("[7,-0.5,1.5E10,0]", Json.encode(listOf(java.util.concurrent.atomic.AtomicInteger(7), number("-0.5"), number("1.5E10"), number("0"))))
        listOf("NaN", "Infinity", "-Infinity", "", "01", "1.", ".5", "+1", "0x10", "1e", "--1", "1 2").forEach {
            assertEquals("[\"$it\"]", Json.encode(listOf(number(it))))
        }
    }

    @Test
    fun nestedMapAndList() {
        assertEquals("{\"a\":[1,true,null]}", Json.encode(mapOf("a" to listOf(1, true, null))))
    }

    @Test
    fun rawJson_isWrittenVerbatim() {
        assertEquals("[{\"x\":1}]", Json.encode(listOf(RawJson("{\"x\":1}"))))
    }

    @Test
    fun cyclicMap_isCutAtMaxDepth() {
        val m = HashMap<String, Any?>()
        m["self"] = m
        val out = Json.encode(m)
        assertTrue(out.contains("\"[max_depth]\""))
    }

    private fun nested(levels: Int): Map<String, Any?> {
        var m: Map<String, Any?> = mapOf("leaf" to "deepest-text")
        repeat(levels - 1) { m = mapOf("c" to m) }
        return m
    }

    @Test
    fun explicitMaxDepth_allowsDeeperValues() {
        val out = Json.encode(nested(100), maxDepth = 256)
        assertFalse(out.contains("[max_depth]"))
        assertTrue(out.contains("deepest-text"))
        val sb = StringBuilder()
        Json.write(nested(100), sb, maxDepth = 256)
        assertEquals(out, sb.toString())
    }

    @Test
    fun defaultMaxDepth_isStill32() {
        assertEquals(32, Json.MAX_DEPTH)
        assertTrue(Json.encode(nested(40)).contains("\"[max_depth]\""))
        assertFalse(Json.encode(nested(32)).contains("[max_depth]"))
        assertTrue(Json.encode(nested(33)).contains("[max_depth]"))
    }

    @Test
    fun cyclicMap_isStillCutWithReplayDepth() {
        val m = HashMap<String, Any?>()
        m["self"] = m
        assertTrue(Json.encode(m, Json.REPLAY_MAX_DEPTH).contains("\"[max_depth]\""))
    }
}
