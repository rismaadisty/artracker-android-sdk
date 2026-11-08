package id.art.tracker.privacy

import id.art.tracker.core.Json
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributeRedactorTest {
    private fun rs(s: String) = AttributeRedactor.redactString(s)
    private fun r(vararg p: Pair<String, Any?>) = AttributeRedactor.redact(mapOf(*p))

    private fun assertMatches(pattern: String, actual: String) =
        assertTrue("'$actual' !~ /$pattern/", Regex(pattern).containsMatchIn(actual))

    // ---- test.each table ----
    @Test
    fun redactsPii_table() {
        val cases = listOf(
            "budi@example.com" to "^b\\*+@example\\.com$",
            "hubungi budi.santoso+x@mail.example.co.id ya" to "b\\*+@mail\\.example\\.co\\.id",
            "4539 1488 0343 6467" to "^\\*{4} \\*{4} \\*{4} 6467$",
            "4539-1488-0343-6467" to "^\\*{4} \\*{4} \\*{4} 6467$",
            "4539148803436467" to "^\\*{4} \\*{4} \\*{4} 6467$",
            "+628123456789" to "^\\+?\\*+6789$",
            "081234567890" to "^\\*+7890$",
            "3201011505900001" to "^\\[redacted\\]$",
            "eyJhbGciOiJIUzI1NiJ9.e30.x" to "^\\[redacted\\]$",
            "Authorization: Bearer abcdef123456" to "\\[redacted\\]",
            "key=art_sec_abcdefghijklmnop" to "\\[redacted\\]",
            "sk_live_abcdefghijklmnop" to "^\\[redacted\\]$",
        )
        for ((input, expected) in cases) assertMatches(expected, rs(input))
    }

    @Test
    fun exactOutputs_forKnownShapes() {
        assertEquals("b***@example.com", rs("budi@example.com"))
        assertEquals("**** **** **** 6467", rs("4539 1488 0343 6467"))
        assertEquals("+********6789", rs("+628123456789"))
        assertEquals("Authorization: Bearer [redacted]", rs("Authorization: Bearer abcdef123456"))
        assertEquals("Basic [redacted]", rs("Basic dXNlcjpwYXNzd29yZA=="))
        assertEquals("key=[redacted]", rs("key=art_sec_abcdefghijklmnop"))
    }

    @Test
    fun sixteenDigitFailingLuhnAndNotNik_isUntouched() {
        assertEquals("1234567812345678", rs("1234567812345678"))
    }

    @Test
    fun epochMillis13Digits_isNotCard() {
        assertEquals("start 1762331598050 end", rs("start 1762331598050 end"))
    }

    @Test
    fun plainTextAndShortNumbersUnchanged() {
        assertEquals("Order #12345 selesai, total 250000", rs("Order #12345 selesai, total 250000"))
    }

    @Test
    fun multiplePiiInOneString() {
        val out = rs("a@b.co dan 4539 1488 0343 6467")
        assertFalse(out.contains("a@b.co"))
        assertFalse(out.contains("4539"))
    }

    @Test
    fun sensitiveQueryParamInsideString() {
        assertEquals("GET /cb?code=[redacted]&page=2", rs("GET /cb?code=abc123&page=2"))
    }

    @Test
    fun keyDenyList_redactsAnyValue() {
        assertEquals(mapOf("password" to "[redacted]", "note" to "ok"), r("password" to "hunter2", "note" to "ok"))
        assertEquals(
            mapOf(
                "userPassword" to "[redacted]",
                "access_token" to "[redacted]",
                "x-api-key" to "[redacted]",
                "cvv" to "[redacted]",
                "card" to "[redacted]",
            ),
            r(
                "userPassword" to "x",
                "access_token" to "y",
                "x-api-key" to "z",
                "cvv" to 1,
                "card" to mapOf("n" to 1),
            ),
        )
    }

    @Test
    fun keyDenyList_doesNotHitPartialWords() {
        val v = mapOf("className" to "btn", "shipping" to "JNE", "passenger" to "ok", "spinner" to true, "cardinality" to 3)
        assertEquals(v, AttributeRedactor.redact(v))
    }

    @Test
    fun sensitiveKey_redacted() {
        assertEquals(
            mapOf("password" to "[redacted]", "userToken" to "[redacted]", "name" to "Budi"),
            r("password" to "x", "userToken" to "y", "name" to "Budi"),
        )
    }

    @Test
    fun recursesIntoListsAndNestedMaps() {
        val out = r(
            "a" to listOf(mapOf("email" to "x"), "budi@example.com"),
            "n" to 5, "ok" to true, "nil" to null,
        )
        @Suppress("UNCHECKED_CAST")
        val a = out["a"] as List<Any?>
        assertEquals(mapOf("email" to "x"), a[0])
        assertMatches("^b\\*+@example\\.com$", a[1] as String)
        assertEquals(5, out["n"])
        assertEquals(true, out["ok"])
        assertTrue(out.containsKey("nil"))
        assertNull(out["nil"])
    }

    @Test
    fun luhnCard_redacted_nonLuhnKept() {
        // Behaviour follows the web port: Luhn-valid cards are masked (web token: last 4 kept), non-Luhn numbers are kept.
        assertEquals("**** **** **** 1111", rs("4111 1111 1111 1111"))
        assertEquals("1234 5678 9012 3456", rs("1234 5678 9012 3456"))
    }

    @Test
    fun nik_validatesProvinceDayMonth() {
        assertEquals("[redacted]", rs("3201011505900001"))
        assertEquals("x [redacted] y", rs("x 3271015501900001 y")) // female: day 55
        assertEquals("1001011505900001", rs("1001011505900001")) // province 10 invalid
        assertEquals("9501011505900001", rs("9501011505900001")) // province 95 invalid
        assertEquals("3201013213900001", rs("3201013213900001")) // month 13 invalid
        assertEquals("3201010005900001", rs("3201010005900001")) // day 00 invalid
    }

    @Test
    fun traversalStopsAtDepth6_webSemantics() {
        var deep: Map<String, Any?> = mapOf("leaf" to "budi@example.com")
        repeat(10) { deep = mapOf("child" to deep) }
        assertFalse(Json.encode(AttributeRedactor.redact(deep)).contains("budi@example.com"))
    }

    @Test
    fun depthBeyond6_isReplacedWithMarker() {
        var deep: Map<String, Any?> = mapOf("leaf" to "x")
        repeat(7) { deep = mapOf("child" to deep) } // 8 map levels in total
        var cur: Any? = AttributeRedactor.redact(deep)
        var levels = 1
        while (cur is Map<*, *>) {
            cur = cur["child"]
            if (cur is Map<*, *>) levels++
        }
        assertEquals(6, levels) // levels 1..6 stay maps
        assertEquals("[max-depth]", cur) // level 7 is the marker
    }

    @Test
    fun circularReferenceDoesNotLoop() {
        val a = HashMap<String, Any?>()
        a["name"] = "x"
        a["self"] = a
        val out = AttributeRedactor.redact(a)
        assertEquals("[circular]", out["self"])
        assertEquals("x", out["name"])
    }

    @Test
    fun cyclicFanOut_isBoundedAndEncodable() {
        val a = HashMap<String, Any?>()
        val b = ArrayList<Any?>()
        repeat(50) { b.add(a) }
        for (i in 0 until 50) a["k$i"] = b
        val out = AttributeRedactor.redact(a)
        val json = Json.encode(out)
        assertTrue(json.length <= AttributeRedactor.MAX_BYTES)
        assertTrue(json.contains("[circular]") || json.contains("_truncated"))
    }

    @Test
    fun sharedNonCyclicReferenceIsStillProcessed() {
        val shared = mapOf("e" to "budi@example.com")
        val out = r("a" to shared, "b" to shared)
        assertFalse(Json.encode(out).contains("budi@example.com"))
        assertFalse(Json.encode(out).contains("[circular]"))
    }

    @Test
    fun traversalCappedAt1000Nodes() {
        // 1500 (not the web's 5000): the [truncated] markers of 5000 entries alone would exceed the 24 KB budget.
        val wide = List(1500) { mapOf("i" to it) }
        val out = r("w" to wide)
        val list = out["w"] as List<*>
        assertTrue(list.count { it is Map<*, *> } <= AttributeRedactor.MAX_NODES)
        assertTrue(list.contains("[truncated]"))
    }

    @Test
    fun dateFunctionAndThrowable() {
        val out = r("d" to Date(0), "f" to { 1 }, "e" to IllegalStateException("boom for budi@example.com"))
        assertEquals("1970-01-01T00:00:00.000Z", out["d"])
        assertFalse(out.containsKey("f"))
        @Suppress("UNCHECKED_CAST")
        val e = out["e"] as Map<String, Any?>
        assertEquals("IllegalStateException", e["name"])
        assertMatches("^boom for b\\*+@example\\.com$", e["message"] as String)
    }

    @Test
    fun throwableInsideAttributes_hasPiiMasked() {
        val out = r("err" to TypeError("gagal untuk budi@example.com"))
        @Suppress("UNCHECKED_CAST")
        val e = out["err"] as Map<String, Any?>
        assertEquals("TypeError", e["name"])
        assertMatches("^gagal untuk b\\*+@example\\.com$", e["message"] as String)
    }

    private class TypeError(m: String) : RuntimeException(m)

    @Test
    fun fastPath_plainTextUnchanged() {
        for (s in listOf("Halaman beranda", "Selamat datang di toko kami", "", "a b c")) assertEquals(s, rs(s))
    }

    @Test
    fun inputIsNotMutated() {
        val inner = mutableMapOf<String, Any?>("email" to "a@b.co", "n" to "budi@example.com")
        val input = mapOf("inner" to inner)
        val snapshot = Json.encode(input)
        AttributeRedactor.redact(input)
        assertEquals(snapshot, Json.encode(input))
    }

    @Test
    fun primitiveArraysAndEnumsAndBigNumbers() {
        val out = r("a" to intArrayOf(1, 2), "b" to java.math.BigInteger("12345678901234567890"), "c" to Thread.State.NEW)
        assertEquals(listOf(1, 2), out["a"])
        assertEquals("12345678901234567890", out["b"])
        assertEquals("NEW", out["c"])
    }

    @Test
    fun overBudget_isTrimmedTo24KB() {
        val big = "x".repeat(1024)
        val attrs = (0 until 100).associate { "k$it" to big }
        val out = AttributeRedactor.redact(attrs)
        assertTrue(Json.encode(out).length <= 24 * 1024)
        assertEquals(true, out["_truncated"])
        assertTrue(out.size > 1)
    }

    @Test
    fun withinBudget_hasNoTruncatedMarker() {
        val out = r("a" to "x".repeat(1000))
        assertFalse(out.containsKey("_truncated"))
    }

    @Test
    fun overBudget_multiByteIsMeasuredInUtf8Bytes() {
        val big = "é".repeat(1024)
        val out = AttributeRedactor.redact((0 until 100).associate { "k$it" to big })
        assertTrue(Json.encode(out).toByteArray(Charsets.UTF_8).size <= 24 * 1024)
        assertNotNull(out["_truncated"])
    }

    @Test
    fun jsWhitespaceSemantics_nbspAfterBearer() {
        assertEquals("Bearer [redacted]", rs("Bearer\u00A0abcdef123456"))
    }

    @Test
    fun asciiWordBoundaries() {
        // JS \b is ASCII-only: a leading non-ASCII letter still counts as a boundary.
        assertEquals("é[redacted]", rs("ésk_live_abcdefghijklmnop"))
    }

    // ---- email: linear time and no recursion (fix round 1) ----

    @Test
    fun longStringWithoutAt_isLinear() {
        // Digits pass the trigger pre-check, and every character is a valid local-part character, so a naive
        // `[A-Za-z0-9._%+-]+@...` regex rescans the whole run from each start position (quadratic, ~73 s here).
        // A separator such as '=' would break the run into short pieces and hide the problem.
        // Generous 2 s bound so the test is not flaky; a regression is orders of magnitude slower.
        val input = "a1".repeat(50_000) // 100 KB, no '@'
        val start = System.nanoTime()
        val out = rs(input)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(input, out)
        assertTrue("took ${ms}ms", ms < 2_000)
    }

    @Test
    fun manyDomainLabels_doesNotOverflowTheStack() {
        val labels = ".b".repeat(50_000)
        // No TLD (labels are single letters): not an email, left untouched, same as the web regex.
        assertEquals("a@b$labels", rs("a@b$labels"))
        // With a TLD at the end the whole address is masked.
        assertEquals("a***@b$labels.com", rs("a@b$labels.com"))
    }

    @Test
    fun longLegitimateEmail_isMasked() {
        assertEquals("u***@sub.sub2.sub3.example.co.id", rs("u.name+tag_1%x@sub.sub2.sub3.example.co.id"))
        assertEquals("hi x***@a-b.example.com, bye", rs("hi x@a-b.example.com, bye"))
    }

    @Test
    fun maskEmails_matchesTheWebRegexOnRandomInputs() {
        val reference = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
        val alphabet = "abcab.. -@1_%+Z" // letters and dots weighted so many strings contain real emails
        val rnd = java.util.Random(42)
        var matched = 0
        repeat(60_000) {
            val s = String(CharArray(rnd.nextInt(24)) { alphabet[rnd.nextInt(alphabet.length)] })
            val expected = reference.replace(s) { m ->
                val at = m.value.indexOf('@')
                "${m.value.substring(0, 1)}***${m.value.substring(at)}"
            }
            if (expected != s) matched++
            assertEquals("input '$s'", expected, AttributeRedactor.maskEmails(s))
        }
        assertTrue("fuzz produced too few matches: $matched", matched > 300)
    }
}
