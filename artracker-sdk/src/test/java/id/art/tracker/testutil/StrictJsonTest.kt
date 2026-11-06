package id.art.tracker.testutil

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StrictJsonTest {
    @Test
    fun accepts_validDocuments() {
        listOf(
            "{}", "[]", "null", "true", "-0", "1.5e+10", "0", """{"a":[1,2,{"b":null}],"c":"\u0000\n\"\\/"}""",
            " { \"k\" : [ ] } ",
        ).forEach { assertNull(it, StrictJson.validate(it)) }
    }

    @Test
    fun rejects_whatLenientParsersAccept() {
        listOf(
            "NaN", "[NaN]", "[Infinity]", "[1,]", "{\"a\":1,}", "{a:1}", "{'a':1}", "[01]", "[1.]", "[.5]", "[+1]",
            "\"a\u0000b\"", "\"a\nb\"", "\"\\x\"", "\"\\u12\"", "[1] 2", "", "{\"a\"}", "[1 2]", "\"abc",
        ).forEach { assertNotNull("should be rejected: ${it.replace("\u0000", "\\0")}", StrictJson.validate(it)) }
    }

    @Test
    fun rejects_documentsDeeperThanTheLimit() {
        assertNotNull(StrictJson.validate("[".repeat(600) + "]".repeat(600)))
        assertNull(StrictJson.validate("[".repeat(500) + "]".repeat(500)))
    }
}
