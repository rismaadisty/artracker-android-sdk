package id.art.tracker.lifecycle

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import id.art.tracker.core.SdkLog

/**
 * [FirstFrameHook] on the decor view of an Activity: the first `onDraw` of its `ViewTreeObserver` means a frame is being
 * drawn; the callback is posted to the FRONT of the main queue, so it runs as soon as that traversal is over (the
 * listener cannot be removed from inside `onDraw` either).
 *
 * The decor is looked up with `peekDecorView()` and never created here: forcing it early would break a host that calls
 * `requestWindowFeature` after `super.onCreate`. If it does not exist yet the hook does nothing. A decor that is not
 * attached yet is waited for: below API 26 a not yet attached view loses the listeners of its observer when it
 * attaches (a platform bug), so the draw listener is added at attach time, on every API level.
 */
internal object DecorViewFirstFrame : FirstFrameHook {
    override fun await(activity: Activity, onFirstFrame: () -> Unit): () -> Unit {
        val decor = activity.window?.peekDecorView() ?: return {}
        val handler = Handler(Looper.getMainLooper())
        var cancelled = false
        var fired = false
        var drawListener: ViewTreeObserver.OnDrawListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null

        fun removeDrawListener(listener: ViewTreeObserver.OnDrawListener) {
            val observer = decor.viewTreeObserver
            if (observer.isAlive) observer.removeOnDrawListener(listener)
        }

        fun addDrawListener() {
            if (cancelled || drawListener != null) return
            val listener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (fired || cancelled) return
                    fired = true
                    handler.postAtFrontOfQueue {
                        runCatching { removeDrawListener(this) }
                        if (!cancelled) onFirstFrame()
                    }
                }
            }
            drawListener = listener
            val observer = decor.viewTreeObserver
            if (observer.isAlive) observer.addOnDrawListener(listener)
        }

        if (decor.isAttachedToWindow) {
            addDrawListener()
        } else {
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    decor.removeOnAttachStateChangeListener(this)
                    attachListener = null
                    addDrawListener()
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            }
            attachListener = listener
            decor.addOnAttachStateChangeListener(listener)
        }

        return {
            cancelled = true
            try {
                drawListener?.let(::removeDrawListener)
                attachListener?.let(decor::removeOnAttachStateChangeListener)
            } catch (t: Throwable) {
                SdkLog.w("cancelling the first frame wait failed", t)
            }
        }
    }
}
