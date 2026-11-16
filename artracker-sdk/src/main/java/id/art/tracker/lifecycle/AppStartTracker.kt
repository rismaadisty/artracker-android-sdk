package id.art.tracker.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import id.art.tracker.core.SdkLog
import java.lang.ref.WeakReference

/** A measured cold start: [durationMs] from the process start to the first drawn frame of [firstScreen]. */
internal data class AppStart(val durationMs: Long, val firstScreen: String)

/** Calls back once the first frame of an Activity is drawn. `await` returns a function that cancels the wait. */
internal fun interface FirstFrameHook {
    /** [onFirstFrame] runs at most once, on the main thread. */
    fun await(activity: Activity, onFirstFrame: () -> Unit): () -> Unit
}

/**
 * One-shot measurement of the cold start: from `Process.getStartUptimeMillis()` to the first frame of the first
 * Activity that gets drawn. Registered as `ActivityLifecycleCallbacks` by `ARTrackerCore.start()`; it releases itself
 * ([onDone]) as soon as it has reported or decided not to, so it reports at most once per process.
 *
 * It reports nothing, on purpose, when the number would mislead:
 * - the first lifecycle callback it hears is not `onActivityCreated`: `init` ran after the first Activity was created;
 * - the process was not in the foreground at `init` ([startedInForeground]): it was started by a broadcast, a service,
 *   WorkManager or a push, and the user may open the app much later;
 * - the first Activity is created more than [MAX_FIRST_CREATE_DELAY_MS] after this tracker was created (`init`): the
 *   process sat idle before it showed anything. The gap is taken from `init`, not from the process start, so a slow
 *   `Application.onCreate` or a heavy ContentProvider does not turn a genuine slow start into "nothing";
 * - the app goes to the background before the first frame, or the duration is not in `0..`[MAX_DURATION_MS].
 * A first Activity that is paused or destroyed before it drew (a splash that finishes, a rotation) is given up, and the
 * next one created is measured instead, from the same process start; its name is the reported `first_screen`. That
 * next Activity must be created within [MAX_NEXT_CREATE_DELAY_MS] of the last lifecycle event, otherwise (a trampoline
 * that finished without starting one, a dialog over the splash, the user coming back much later) nothing is reported.
 *
 * Both times are on the `SystemClock.uptimeMillis` clock; no I/O. Main thread only, no locks.
 */
internal class AppStartTracker(
    private val processStartUptimeMs: Long,
    private val startedInForeground: Boolean,
    private val uptime: () -> Long,
    private val firstFrame: FirstFrameHook,
    private val onAppStart: (AppStart) -> Unit,
    private val onDone: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    /** The clock at creation, which is `init`: the first Activity must follow it closely. */
    private val initUptimeMs = uptime()
    private var lastEventUptimeMs = initUptimeMs
    private var done = false
    private var heardFirstCallback = false
    private var candidate: WeakReference<Activity>? = null
    private var cancelWait: (() -> Unit)? = null

    val isDone: Boolean get() = done

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = guard {
        if (done) return@guard
        if (!heardFirstCallback) {
            heardFirstCallback = true
            if (!startedInForeground) return@guard finish()
            if (uptime() - initUptimeMs > MAX_FIRST_CREATE_DELAY_MS) return@guard finish()
        } else if (uptime() - lastEventUptimeMs > MAX_NEXT_CREATE_DELAY_MS) {
            return@guard finish() // too long after the last event: not the same launch
        }
        cancelHook() // a newer Activity replaces a candidate that has not drawn yet
        candidate = WeakReference(activity)
        lastEventUptimeMs = uptime()
    }

    override fun onActivityResumed(activity: Activity) = guard {
        if (skipIfNotTheFirstCallback()) return@guard
        if (candidate?.get() !== activity) return@guard
        cancelHook()
        cancelWait = firstFrame.await(activity) { onFrame(activity) }
    }

    override fun onActivityPaused(activity: Activity) = guard {
        if (skipIfNotTheFirstCallback()) return@guard
        giveUp(activity)
    }

    override fun onActivityDestroyed(activity: Activity) = guard {
        if (skipIfNotTheFirstCallback()) return@guard
        giveUp(activity)
    }

    override fun onActivityStarted(activity: Activity) = guard { skipIfNotTheFirstCallback() }

    override fun onActivityStopped(activity: Activity) = guard { skipIfNotTheFirstCallback() }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = guard { skipIfNotTheFirstCallback() }

    /** The app went to the background before the first frame: nothing is reported, now or later. */
    fun onAppBackground() = guard {
        if (!done) finish()
    }

    /** The first thing heard was not `onActivityCreated`: `init` came late. True when this call ended the work. */
    private fun skipIfNotTheFirstCallback(): Boolean {
        if (done) return true
        lastEventUptimeMs = uptime()
        if (heardFirstCallback) return false
        heardFirstCallback = true
        finish()
        return true
    }

    private fun giveUp(activity: Activity) {
        if (done || candidate?.get() !== activity) return
        cancelHook()
        candidate = null // a splash or a rotation: wait for the next Activity that is created
    }

    private fun onFrame(activity: Activity) = guard {
        // A callback of a candidate that was given up, or of a tracker that is done, is stale.
        if (done || candidate?.get() !== activity) return@guard
        cancelWait = null // it fired: nothing left to cancel
        val duration = uptime() - processStartUptimeMs
        if (duration in 0..MAX_DURATION_MS) {
            try {
                onAppStart(AppStart(duration, screenNameOf(activity.javaClass)))
            } catch (t: Throwable) {
                SdkLog.w("reporting the app start failed", t)
            }
        }
        finish()
    }

    private fun cancelHook() {
        val cancel = cancelWait ?: return
        cancelWait = null
        try {
            cancel()
        } catch (t: Throwable) {
            SdkLog.w("cancelling the first frame wait failed", t)
        }
    }

    private fun finish() {
        done = true
        cancelHook()
        candidate = null
        try {
            onDone()
        } catch (t: Throwable) {
            SdkLog.w("releasing the app start tracker failed", t)
        }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            SdkLog.w("app start tracking failed", t)
        }
    }

    companion object {
        /** A first Activity created later than this after `init` means the process was not started to show it. */
        const val MAX_FIRST_CREATE_DELAY_MS = 1_000L

        /** After an Activity was given up, the next one must be created within this long of the last lifecycle event. */
        const val MAX_NEXT_CREATE_DELAY_MS = 2_000L

        /** A longer "cold start" is not a start (a process that sat in the background first): dropped. */
        const val MAX_DURATION_MS = 60_000L
    }
}
