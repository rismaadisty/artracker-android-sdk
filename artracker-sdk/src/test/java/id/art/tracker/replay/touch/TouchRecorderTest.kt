package id.art.tracker.replay.touch

import android.app.Activity
import android.view.MotionEvent
import android.view.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchRecorderTest {
    private val log = ArrayList<String>()
    private val touches = ArrayList<Triple<TouchKind, Float, Float>>()
    private val record: (TouchKind, Float, Float) -> Unit = { k, x, y ->
        log.add("record:$k")
        touches.add(Triple(k, x, y))
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** A delegate that logs its calls and returns [result] from dispatchTouchEvent. */
    private class Delegate(base: Window.Callback, private val log: MutableList<String>, var result: Boolean) :
        Window.Callback by base {
        var focusChanges = 0
        var pointerCapture = 0

        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            log.add("delegate")
            return result
        }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            focusChanges++
        }

        override fun onPointerCaptureChanged(hasCapture: Boolean) {
            pointerCapture++
        }
    }

    private fun event(action: Int, t: Long, x: Float = 10f, y: Float = 20f): MotionEvent =
        MotionEvent.obtain(0, t, action, x, y, 0)

    @Test
    fun forwardsDelegateResult() {
        val delegate = Delegate(activity(), log, result = false)
        val recorder = TouchRecorder(delegate, record)
        assertFalse(recorder.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0)))
        delegate.result = true
        assertTrue(recorder.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 5)))
    }

    @Test
    fun delegateCalledBeforeRecording() {
        val recorder = TouchRecorder(Delegate(activity(), log, result = true), record)
        recorder.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0))
        assertEquals(listOf("delegate", "record:DOWN"), log)
    }

    @Test
    fun recordsDownAndUp() {
        val recorder = TouchRecorder(Delegate(activity(), log, result = true), record)
        recorder.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0, 11f, 22f))
        recorder.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 30, 33f, 44f))
        assertEquals(listOf(Triple(TouchKind.DOWN, 11f, 22f), Triple(TouchKind.UP, 33f, 44f)), touches)
    }

    @Test
    fun cancel_isRecordedAsUp() {
        val recorder = TouchRecorder(Delegate(activity(), log, result = true), record)
        recorder.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, 0))
        assertEquals(listOf(TouchKind.UP), touches.map { it.first })
    }

    @Test
    fun moveThrottled() {
        val recorder = TouchRecorder(Delegate(activity(), log, result = true), record)
        for (t in longArrayOf(1_000, 1_010, 1_049, 1_050, 1_099, 1_100, 1_149)) {
            recorder.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, t, x = t.toFloat()))
        }
        assertEquals(listOf(1_000f, 1_050f, 1_100f), touches.map { it.second })
        assertTrue(touches.all { it.first == TouchKind.MOVE })
    }

    @Test
    fun recordingFailure_isSwallowed_delegateResultKept() {
        val recorder = TouchRecorder(Delegate(activity(), log, result = true)) { _, _, _ -> throw IllegalStateException("boom") }
        assertTrue(recorder.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0)))
    }

    @Test
    fun otherCallbacks_areForwarded() {
        val delegate = Delegate(activity(), log, result = true)
        val recorder = TouchRecorder(delegate, record)
        recorder.onWindowFocusChanged(true)
        recorder.onPointerCaptureChanged(true)
        assertEquals(1, delegate.focusChanges)
        assertEquals(1, delegate.pointerCapture)
    }

    @Test
    fun wrap_isIdempotent() {
        val activity = activity()
        val window = activity.window
        val first = TouchRecorder.wrap(window) { _, _, _ -> log.add("old") }
        val second = TouchRecorder.wrap(window, record)
        assertNotNull(first)
        assertSame(first, second)
        assertSame(first, window.callback)
        assertSame(activity, first!!.delegate)

        window.callback.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0))
        assertEquals(listOf("record:DOWN"), log) // recorded once, by the rebound callback
        TouchRecorder.unwrap(window)
    }

    @Test
    fun unwrap_restoresOnlyIfOurs() {
        val activity = activity()
        val window = activity.window
        TouchRecorder.wrap(window, record)
        TouchRecorder.unwrap(window)
        assertSame(activity, window.callback)

        // Another SDK wraps after us: unwrapping must not drop its wrapper, ours becomes a pass-through.
        val ours = TouchRecorder.wrap(window, record)!!
        val foreignForwarding = object : Window.Callback by window.callback {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                log.add("foreign")
                return ours.dispatchTouchEvent(event)
            }
        }
        window.callback = foreignForwarding
        TouchRecorder.unwrap(window)
        assertSame(foreignForwarding, window.callback)
        window.callback.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0))
        assertTrue(touches.isEmpty())
        assertEquals(listOf("foreign"), log)

        // Wrapping again re-activates the recorder still in the chain instead of wrapping twice.
        val again = TouchRecorder.wrap(window, record)
        assertSame(ours, again)
        assertSame(foreignForwarding, window.callback)
        window.callback.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 10))
        assertEquals(listOf(TouchKind.UP), touches.map { it.first })
        TouchRecorder.unwrap(window)
    }
}
