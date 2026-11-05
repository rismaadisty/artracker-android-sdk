package id.art.tracker.lifecycle

import id.art.tracker.ItemType
import id.art.tracker.core.ItemFactory
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.ScreenCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTrackerTest {
    @Test
    fun emitsWithPrevious() {
        val c = ScreenCapture()
        assertNull(c.tracker.currentScreen)

        c.tracker.onScreen("Home", "activity")
        c.tracker.onScreen("Checkout", "fragment")

        assertEquals(2, c.emitted.size)
        val first = c.emitted[0]
        assertEquals(ItemType.EVENT, first.type)
        assertEquals("screen_view", first.name)
        assertEquals(mapOf("screen" to "Home", "type" to "activity"), first.attributes)
        assertFalse(first.attributes.containsKey("previous"))
        assertEquals(
            mapOf("screen" to "Checkout", "previous" to "Home", "type" to "fragment"),
            c.emitted[1].attributes,
        )
        assertEquals("Checkout", c.tracker.currentScreen)
    }

    @Test
    fun duplicateSuppressed() {
        val c = ScreenCapture()
        c.tracker.onScreen("Home", "activity")
        c.tracker.onScreen("Home", "activity")
        c.tracker.onScreen("Home", "fragment") // same screen name, different source: still the same screen
        assertEquals(1, c.emitted.size)

        c.tracker.onScreen("Cart", "compose")
        c.tracker.onScreen("Home", "activity") // not consecutive any more: emitted again
        assertEquals(listOf("Home:activity", "Cart:compose", "Home:activity"), c.screens())
        assertEquals("Cart", c.emitted[2].attributes["previous"])
    }

    @Test
    fun blankNamesAreIgnored() {
        val c = ScreenCapture()
        c.tracker.onScreen("  ", "manual")
        assertEquals(0, c.emitted.size)
        assertNull(c.tracker.currentScreen)
    }

    @Test
    fun onScreenChangedSeam_receivesPreviousAndCurrent_forEmittedChangesOnly() {
        val calls = ArrayList<Pair<String?, String>>()
        val emitted = ArrayList<Any>()
        val t = ScreenTracker(
            { emitted += it },
            ItemFactory(FakeClock(), { "s" }, { null }),
            onScreenChanged = { prev, cur -> calls += prev to cur },
        )
        t.onScreen("A", "activity")
        t.onScreen("A", "activity")
        t.onScreen("B", "activity")
        assertEquals(listOf(null to "A", "A" to "B"), calls)
        assertEquals(2, emitted.size)
    }
}
