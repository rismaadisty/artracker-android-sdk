package id.art.tracker.replay.touch

import android.os.Build
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal enum class TouchKind { DOWN, MOVE, UP }

/**
 * Observes touches of one window by wrapping its `Window.Callback` (ARCHITECTURE.md section 6.4). Every call is
 * forwarded to [delegate]; only [dispatchTouchEvent] is intercepted, and only to READ coordinates after the
 * delegate handled the event: the delegate's result is returned unchanged and nothing is ever consumed.
 *
 * - DOWN and UP (CANCEL counts as UP) are always reported; MOVE at most once per [MOVE_THROTTLE_MS] of event time.
 * - Coordinates are raw screen pixels; the caller converts them.
 * - [onTouch] failures are swallowed; a delegate exception propagates as if the wrapper were not there.
 * - While [active] is false the wrapper is a pure pass-through (see [unwrap]).
 *
 * Main thread only (where the framework dispatches input).
 */
internal class TouchRecorder(
    internal val delegate: Window.Callback,
    onTouch: (TouchKind, Float, Float) -> Unit,
) : Window.Callback by delegate {
    @Volatile
    internal var onTouch: (TouchKind, Float, Float) -> Unit = onTouch

    @Volatile
    internal var active: Boolean = true

    private var hasMove = false
    private var lastMoveTime = 0L

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        val result = delegate.dispatchTouchEvent(event)
        if (active && event != null) {
            try {
                record(event)
            } catch (_: Throwable) {
                // Recording must never affect the app.
            }
        }
        return result
    }

    private fun record(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onTouch(TouchKind.DOWN, event.rawX, event.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTouch(TouchKind.UP, event.rawX, event.rawY)
            MotionEvent.ACTION_MOVE -> {
                val t = event.eventTime
                if (!hasMove || t - lastMoveTime >= MOVE_THROTTLE_MS) {
                    hasMove = true
                    lastMoveTime = t
                    onTouch(TouchKind.MOVE, event.rawX, event.rawY)
                }
            }
        }
    }

    // Interface methods with a Java default body are forwarded explicitly, so the delegate's own implementation
    // (not the interface default) always runs, whatever the compiler does for class delegation.
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) {
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) delegate.onPointerCaptureChanged(hasCapture)
    }

    companion object {
        const val MOVE_THROTTLE_MS: Long = 50

        /** Recorder installed per window, still known if another wrapper was installed on top of it. */
        private val installed = WeakHashMap<Window, WeakReference<TouchRecorder>>()

        /**
         * Installs a recorder on [window], or re-activates and rebinds the one already there (directly, or behind
         * another SDK's wrapper), so a window is never wrapped twice. Null if the window has no callback or
         * anything fails.
         */
        fun wrap(window: Window, onTouch: (TouchKind, Float, Float) -> Unit): TouchRecorder? = try {
            synchronized(installed) {
                val current = window.callback
                val existing = (current as? TouchRecorder) ?: installed[window]?.get()
                if (existing != null) {
                    existing.onTouch = onTouch
                    existing.active = true
                    installed[window] = WeakReference(existing)
                    existing
                } else if (current == null) {
                    null
                } else {
                    TouchRecorder(current, onTouch).also {
                        window.callback = it
                        installed[window] = WeakReference(it)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }

        /**
         * Restores the original callback if our recorder is still the window's callback. If something wrapped
         * the window after us, its wrapper is left in place and ours only stops recording (pass-through).
         */
        fun unwrap(window: Window) {
            try {
                synchronized(installed) {
                    val current = window.callback
                    if (current is TouchRecorder) {
                        current.active = false
                        window.callback = current.delegate
                        installed.remove(window)
                    } else {
                        installed[window]?.get()?.active = false
                    }
                }
            } catch (_: Throwable) {
                // Never let cleanup break the app.
            }
        }
    }
}
