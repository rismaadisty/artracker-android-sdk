package id.art.tracker.core

import org.junit.Assert.assertEquals
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
}
