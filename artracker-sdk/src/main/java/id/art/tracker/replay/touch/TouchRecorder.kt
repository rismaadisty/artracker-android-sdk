package id.art.tracker.replay.touch

import android.os.Build
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal enum class TouchKind { DOWN, MOVE, UP }

/**
 * Observes input of one window by wrapping its `Window.Callback` (ARCHITECTURE.md section 6.4). Every call is
 * forwarded to [delegate]; only [dispatchTouchEvent], [dispatchKeyEvent] and [onWindowFocusChanged] are intercepted,
 * and only to READ: the delegate's result is returned unchanged and nothing is ever consumed.
 *
 * Two independent observers share the one wrapper:
 * - [onInput] (core-owned, always installed): fired on touch DOWN and key DOWN, whatever replay is doing, so the
 *   analytics session counts user activity even when replay is off. Always fired before [onTouch] for the same event.
 * - [onTouch] / [onFocusChanged] (replay-owned, bound only while replay records): DOWN and UP (CANCEL counts as UP)
 *   are always reported; MOVE at most once per [MOVE_THROTTLE_MS] of event time. Coordinates are raw screen pixels.
 *
 * Observer failures are swallowed; a delegate exception propagates as if the wrapper were not there. While [active]
 * is false the wrapper is a pure pass-through (see [uninstall]).
 *
 * Main thread only (where the framework dispatches input).
 */
internal class TouchRecorder(
    internal val delegate: Window.Callback,
    onTouch: ((TouchKind, Float, Float) -> Unit)? = null,
) : Window.Callback by delegate {
    @Volatile
    internal var onTouch: ((TouchKind, Float, Float) -> Unit)? = onTouch

    @Volatile
    internal var onInput: (() -> Unit)? = null

    @Volatile
    internal var onFocusChanged: ((Boolean) -> Unit)? = null

    @Volatile
    internal var active: Boolean = true

    private var hasMove = false
    private var lastMoveTime = 0L

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        val result = delegate.dispatchTouchEvent(event)
        if (active && event != null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) notifyInput()
            try {
                record(event)
            } catch (_: Throwable) {
                // Recording must never affect the app.
            }
        }
        return result
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        val result = delegate.dispatchKeyEvent(event)
        if (active && event != null && event.action == KeyEvent.ACTION_DOWN) notifyInput()
        return result
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        delegate.onWindowFocusChanged(hasFocus)
        if (!active) return
        try {
            onFocusChanged?.invoke(hasFocus)
        } catch (_: Throwable) {
            // Observing must never affect the app.
        }
    }

    private fun notifyInput() {
        try {
            onInput?.invoke()
        } catch (_: Throwable) {
            // Observing must never affect the app.
        }
    }

    private fun record(event: MotionEvent) {
        val onTouch = onTouch ?: return
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

        /** The recorder already on [window] (directly, or behind another wrapper), else a new one; null if impossible. */
        private fun obtain(window: Window): TouchRecorder? {
            val current = window.callback
            val existing = (current as? TouchRecorder) ?: installed[window]?.get()
            if (existing != null) {
                existing.active = true
                installed[window] = WeakReference(existing)
                return existing
            }
            if (current == null) return null
            return TouchRecorder(current).also {
                window.callback = it
                installed[window] = WeakReference(it)
            }
        }

        /**
         * Core side: makes sure [window] has a recorder (wrapping it at most once, even behind another SDK's
         * wrapper) and routes user input to [onInput]. Null if the window has no callback or anything fails.
         */
        fun install(window: Window, onInput: () -> Unit): TouchRecorder? = try {
            synchronized(installed) { obtain(window)?.also { it.onInput = onInput } }
        } catch (_: Throwable) {
            null
        }

        /** Replay side: starts recording touches and focus changes on [window] (installing a recorder if needed). */
        fun bindReplay(
            window: Window,
            onTouch: (TouchKind, Float, Float) -> Unit,
            onFocusChanged: ((Boolean) -> Unit)?,
        ): TouchRecorder? = try {
            synchronized(installed) {
                obtain(window)?.also {
                    it.onTouch = onTouch
                    it.onFocusChanged = onFocusChanged
                }
            }
        } catch (_: Throwable) {
            null
        }

        /** Replay side: stops recording. The wrapper and the input observer stay (the core owns them). */
        fun unbindReplay(window: Window) {
            try {
                synchronized(installed) {
                    val recorder = (window.callback as? TouchRecorder) ?: installed[window]?.get()
                    recorder?.onTouch = null
                    recorder?.onFocusChanged = null
                }
            } catch (_: Throwable) {
                // Never let cleanup break the app.
            }
        }

        /**
         * Core side (activity paused): restores the original callback if our recorder is still the window's
         * callback. If something wrapped the window after us, its wrapper is left in place and ours only goes
         * pass-through.
         */
        fun uninstall(window: Window) {
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
