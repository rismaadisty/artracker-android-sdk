package id.art.tracker.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StacktraceTest {
    private fun fail(message: String): RuntimeException = RuntimeException(message)

    private fun failWithOtherClass(message: String): IllegalStateException = IllegalStateException(message)

    private fun deepTrace(frames: Int, cause: Throwable? = null): Throwable {
        val t = RuntimeException("deep", cause)
        t.stackTrace = Array(frames) { StackTraceElement("com.example.Deep$it", "method$it", "Deep.kt", it) }
        return t
    }

    @Test
    fun includesCauseChain() {
        val root = IllegalArgumentException("root cause")
        val mid = IllegalStateException("middle", root)
        val top = RuntimeException("top", mid)

        val text = Stacktrace.format(top)

        val lines = text.lines()
        assertEquals("java.lang.RuntimeException: top", lines[0])
        assertTrue(text.contains("\nCaused by: java.lang.IllegalStateException: middle"))
        assertTrue(text.contains("\nCaused by: java.lang.IllegalArgumentException: root cause"))
        assertTrue(text.indexOf("middle") < text.indexOf("root cause"))
        assertTrue(text.contains("\tat id.art.tracker.telemetry.StacktraceTest.includesCauseChain"))
        assertFalse(text.contains("[truncated]"))
    }

    @Test
    fun truncatedTo16KB() {
        val t = deepTrace(5_000)

        val text = Stacktrace.format(t)

        assertTrue("size ${text.toByteArray().size}", text.toByteArray(Charsets.UTF_8).size <= 16_384)
        assertTrue(text.length > 15_000) // it used (nearly) the whole budget
        assertTrue(text.startsWith("java.lang.RuntimeException: deep\n\tat com.example.Deep0.method0(Deep.kt:0)"))
        assertTrue(text.endsWith("[truncated]"))
        assertFalse(text.contains("Deep4999"))
    }

    @Test
    fun truncation_respectsByteBudgetForMultiByteText() {
        val t = RuntimeException("é".repeat(2_000))
        t.stackTrace = emptyArray()
        val text = Stacktrace.format(t, maxBytes = 512)
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= 512)
        assertTrue(text.endsWith("[truncated]"))
    }

    @Test
    fun causeChainIsCappedAndCycleSafe() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // a -> b -> a
        val text = Stacktrace.format(a)
        assertEquals(1, Regex("Caused by: java.lang.RuntimeException: b").findAll(text).count())

        var chain: Throwable = RuntimeException("c0")
        for (i in 1..50) chain = RuntimeException("c$i", chain)
        val long = Stacktrace.format(chain)
        assertTrue(long.endsWith("[truncated]"))
        assertTrue(Regex("Caused by:").findAll(long).count() <= 9)
    }

    @Test
    fun brokenToString_doesNotThrow() {
        val t = object : RuntimeException() {
            override fun toString(): String = throw IllegalStateException("no")
        }
        assertTrue(Stacktrace.format(t).startsWith("id.art.tracker.telemetry.StacktraceTest$"))
    }

    @Test
    fun fingerprint_ignoresMessage() {
        val a = fail("user 1 failed")
        val b = fail("completely different text 42")

        assertEquals(Stacktrace.fingerprint(a), Stacktrace.fingerprint(b))
        assertTrue(Regex("[0-9a-f]{40}").matches(Stacktrace.fingerprint(a)))
    }

    @Test
    fun fingerprint_ignoresLineNumbers_butNotClassOrFrames() {
        val frames = { line: Int ->
            Array(8) { StackTraceElement("com.example.C$it", "m$it", "C.kt", line + it) }
        }
        val a = RuntimeException("x").apply { stackTrace = frames(10) }
        val b = RuntimeException("x").apply { stackTrace = frames(900) }
        assertEquals(Stacktrace.fingerprint(a), Stacktrace.fingerprint(b))

        val otherClass = IllegalStateException("x").apply { stackTrace = frames(10) }
        assertNotEquals(Stacktrace.fingerprint(a), Stacktrace.fingerprint(otherClass))

        val otherTop = RuntimeException("x").apply {
            stackTrace = arrayOf(StackTraceElement("com.example.Other", "m0", "C.kt", 1)) + frames(10).drop(1)
        }
        assertNotEquals(Stacktrace.fingerprint(a), Stacktrace.fingerprint(otherTop))

        // Only the 5 top frames count.
        val deeper = RuntimeException("x").apply {
            stackTrace = frames(10).take(5).toTypedArray() +
                StackTraceElement("com.example.Different", "z", "Z.kt", 1)
        }
        assertEquals(Stacktrace.fingerprint(a), Stacktrace.fingerprint(deeper))
    }

    @Test
    fun fingerprint_differsAcrossCallSites() {
        assertNotEquals(Stacktrace.fingerprint(fail("x")), Stacktrace.fingerprint(failWithOtherClass("x")))
    }

    @Test
    fun summary_isClassAndMessage() {
        assertEquals("java.lang.RuntimeException: boom", Stacktrace.summary(RuntimeException("boom")))
        assertEquals("java.lang.RuntimeException", Stacktrace.summary(RuntimeException()))
    }
}
