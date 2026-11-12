package id.art.tracker.perf

import android.app.Activity
import android.os.Handler
import android.view.Window
import id.art.tracker.lifecycle.ActivityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeRegistry : WindowListenerRegistry {
    val listeners = ArrayList<ActivityTracker.WindowListener>()

    override fun add(l: ActivityTracker.WindowListener) {
        listeners += l
    }

    override fun remove(l: ActivityTracker.WindowListener) {
        listeners -= l
    }

    fun resume(a: Activity) = listeners.toList().forEach { it.onActivityResumed(a) }

    fun pause(a: Activity) = listeners.toList().forEach { it.onActivityPaused(a) }
}

private class RecordingAttacher : FrameWindowAttacher {
    val attached = ArrayList<Window>()
    val detached = ArrayList<Window>()
    var listener: Window.OnFrameMetricsAvailableListener? = null
    var handler: Handler? = null

    override fun attach(window: Window, listener: Window.OnFrameMetricsAvailableListener, handler: Handler) {
        attached += window
        this.listener = listener
        this.handler = handler
    }

    override fun detach(window: Window, listener: Window.OnFrameMetricsAvailableListener) {
        detached += window
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameMetricsFrameSourceTest {
    private val registry = FakeRegistry()
    private val attacher = RecordingAttacher()
    private val source = FrameMetricsFrameSource(registry, attacher = attacher)
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `resume after start attaches the listener and pause detaches it`() {
        source.start()
        assertEquals(1, registry.listeners.size)
        registry.resume(activity)
        assertEquals(listOf(activity.window), attacher.attached)
        assertNotNull(attacher.handler)
        registry.pause(activity)
        assertEquals(listOf(activity.window), attacher.detached)
        source.stop()
    }

    @Test
    fun `nothing is created before start`() {
        assertNull(source.thread)
        assertTrue(registry.listeners.isEmpty())
    }

    @Test
    fun `stop detaches the current window unregisters and quits the thread`() {
        source.start()
        registry.resume(activity)
        val thread = source.thread!!
        assertTrue(thread.isAlive)
        source.stop()
        assertEquals(listOf(activity.window), attacher.detached)
        assertTrue(registry.listeners.isEmpty())
        thread.join(2_000)
        assertFalse(thread.isAlive)
    }

    @Test
    fun `frames reported by the platform feed the accumulator`() {
        source.start()
        registry.resume(activity)
        // Stand in for the platform callback: the source converts FrameMetrics to a duration in ns.
        source.onFrameDuration(40_000_000L, 60f)
        source.onFrameDuration(10_000_000L, 60f)
        val w = source.drain()
        assertEquals(2, w.frames)
        assertEquals(1, w.jank)
        source.stop()
    }

    @Test
    fun `start twice does not register twice`() {
        source.start()
        source.start()
        assertEquals(1, registry.listeners.size)
        source.stop()
    }
}
