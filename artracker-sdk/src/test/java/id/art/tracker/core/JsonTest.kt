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
