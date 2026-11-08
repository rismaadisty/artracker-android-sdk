package id.art.tracker.playground.scenario

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapRegistryTest {
    private fun target(left: Float, inActivity: Boolean = true) = TapTarget(Rect(left, 0f, left + 10f, 10f), inActivity)

    @Test
    fun registerThenFindReturnsTheTarget() {
        val r = TapRegistry()
        r.register("a", target(1f))
        assertEquals(target(1f), r.find("a"))
    }

    @Test
    fun unregisterRemovesIt() {
        val r = TapRegistry()
        r.register("a", target(1f))
        r.unregister("a")
        assertNull(r.find("a"))
    }

    @Test
    fun registeringTwiceKeepsTheLatest() {
        val r = TapRegistry()
        r.register("a", target(1f))
        r.register("a", target(2f))
        assertEquals(target(2f), r.find("a"))
    }

    @Test
    fun unknownIdIsNull() {
        assertNull(TapRegistry().find("nope"))
    }
}
