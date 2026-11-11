package id.art.tracker.perf

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWarningCallbacksTest {
    private var foreground = true
    private val levels = ArrayList<Int>()
    private val callbacks = MemoryWarningCallbacks({ foreground }, { levels += it })

    @Test
    fun `level 10 in the foreground emits once`() {
        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(listOf(10), levels)
    }

    @Test
    fun `level 15 in the foreground emits`() {
        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(listOf(15), levels)
    }

    @Test
    fun `other levels emit nothing`() {
        listOf(5, 20, 40, 60, 80, 0, -1).forEach(callbacks::onTrimMemory)
        assertTrue(levels.isEmpty())
    }

    @Test
    fun `nothing is emitted in the background`() {
        foreground = false
        callbacks.onTrimMemory(10)
        callbacks.onTrimMemory(15)
        assertTrue(levels.isEmpty())
    }

    @Test
    fun `a failing emit never escapes`() {
        MemoryWarningCallbacks({ true }, { error("boom") }).onTrimMemory(10)
    }
}
