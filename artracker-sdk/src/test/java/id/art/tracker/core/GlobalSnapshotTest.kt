package id.art.tracker.core

import id.art.tracker.ItemType
import id.art.tracker.LogLevel
import id.art.tracker.privacy.AttributeRedactor
import id.art.tracker.testutil.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a snapshot of global properties does to one item, on the pipeline thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlobalSnapshotTest {
    private val factory = ItemFactory(FakeClock(1_000), { "s1" }, { null })

    private fun snap(vararg pairs: Pair<String, Any?>) = GlobalSnapshot(linkedMapOf(*pairs))

    @Test
    fun globalsAreAddedAfterTheItemsOwnKeys() {
        val out = snap("g" to "x").applyTo(factory.event("e", linkedMapOf("a" to 1)))
        assertEquals(listOf("a", "g"), out.attributes.keys.toList())
    }

    @Test
    fun itemKeyWins_evenWhenItsValueIsNull() {
        val out = snap("plan" to "pro").applyTo(factory.event("e", mapOf("plan" to null)))
        assertTrue("plan" in out.attributes)
        assertNull(out.attributes["plan"])
    }

    @Test
    fun perfItemsAreLeftUntouched() {
        val item = factory.perf(mapOf("cpu" to 1))
        assertSame(item, snap("g" to "x").applyTo(item))
    }

    @Test
    fun eventLogAndSessionItemsGetGlobals() {
        val g = snap("g" to "x")
        assertEquals("x", g.applyTo(factory.event("e")).attributes["g"])
        assertEquals("x", g.applyTo(factory.log(LogLevel.INFO, "l", "m")).attributes["g"])
        assertEquals("x", g.applyTo(factory.session("session_start")).attributes["g"])
        assertEquals(ItemType.SESSION, g.applyTo(factory.session("session_start")).type)
    }

    @Test
    fun emptySnapshot_returnsTheSameItem() {
        val item = factory.event("e", mapOf("a" to 1))
        assertSame(item, GlobalSnapshot.EMPTY.applyTo(item))
    }

    @Test
    fun throwingGlobalValue_costsOnlyThatEntry() {
        val bad = object {
            override fun toString(): String = throw AssertionError("hostile toString")
        }
        val out = snap("bad" to bad, "ok" to 1).applyTo(factory.event("e"))
        assertEquals(1, out.attributes["ok"])
        assertFalse("bad" in out.attributes)
    }

    @Test
    fun sensitiveGlobals_areRedacted() {
        val out = snap("password" to "hunter2", "contact" to "budi@example.com").applyTo(factory.event("e"))
        assertEquals(AttributeRedactor.REDACTED, out.attributes["password"])
        assertEquals(AttributeRedactor.redactString("budi@example.com"), out.attributes["contact"])
    }

    @Test
    fun hugeGlobals_areCappedToTheAttributeBudget() {
        val kb = "x".repeat(1024)
        val huge = LinkedHashMap<String, Any?>()
        for (i in 0 until 10_000) huge["k$i"] = kb
        val out = GlobalSnapshot(huge).applyTo(factory.event("e"))

        assertTrue(Json.encode(out.attributes).toByteArray(Charsets.UTF_8).size <= AttributeRedactor.MAX_BYTES)
        assertFalse("the cap is applied to the globals, not left to the final truncation", "_truncated" in out.attributes)
        assertTrue(out.attributes.isNotEmpty())
    }

    @Test
    fun vettingRunsOncePerSnapshot() {
        var calls = 0
        val counted = object {
            override fun toString(): String {
                calls++
                return "value"
            }
        }
        val g = snap("c" to counted)
        repeat(50) { g.applyTo(factory.event("e$it")) }
        assertEquals(1, calls)
    }
}
