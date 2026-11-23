package id.art.tracker.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import id.art.tracker.core.SdkLog
import java.lang.ref.WeakReference

/**
 * Measures warm and hot starts: the time from the moment an Activity of an already running process begins to appear to
 * its first drawn frame.
 * - **warm**: the Activity is created (the process was alive, the Activity was not). Anchor: its `onActivityPreCreated`
 *   (API 29+) when it has one, else its `onActivityCreated`. Phases `activity_create`, `first_draw`.
 * - **hot**: the Activity is started without being created (it was only stopped). Anchor: `onActivityStarted`. Phases
 *   `activity_start`, `first_draw`.
 *
 * It measures nothing until [arm] is called: the core arms it when the process is known not to have had a cold start
 * (a background launch, a late first Activity) and every time the app comes back from the background. Unarmed, an
 * Activity that was already alive when the SDK started, or an Activity that merely starts and stops, is never a start.
 * It disarms after it reported, after it dropped, or when the duration is over [MAX_WARM_HOT_MS].
 *
 * A measured Activity that is paused or destroyed before its first frame (a rotation, a router, a lock screen) hands
 * the start to the next Activity that is created within [MAX_HANDOFF_MS] of the last event, keeping the same anchor; a
 * newer Activity created while one is still undrawn takes over the same way. Anything else that resumes during the
 * handoff, or a replacement that comes later, drops the start. Going to the background before the first frame drops the
 * pending start; the tracker re-arms itself for the next return.
 *
 * Both times are on the `SystemClock.uptimeMillis` clock; no I/O. Main thread only, no locks.
 */
internal class WarmHotStartTracker(
    private val uptime: () -> Long,
    private val firstFrame: FirstFrameHook,
    private val onAppStart: (AppStart) -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var armed = false

    /** The latest `onActivityPreCreated`, remembered even when unarmed: the core arms inside the `onActivityCreated` dispatch. */
    private var lastPreCreated: Pair<WeakReference<Activity>, Long>? = null

    // The pending start; `type == null` means none.
    private var type: StartType? = null
    private var anchorMs = 0L
    private var measured: WeakReference<Activity>? = null
    private var resumedMs: Long? = null

    /** The measured Activity was destroyed before its frame: the next one created takes over. */
    private var handoff = false
    private var lastEventMs = 0L
    private var cancelWait: (() -> Unit)? = null

    /** The next Activity created (-> warm) or, failing that, started (-> hot) is measured. Called by the core. */
    fun arm() = guard { armed = true }

    /** Background before the first frame: the pending start is dropped and the tracker re-arms itself. */
    fun onAppBackground() = guard {
        val pending = type != null
        clearPending()
        if (pending) {
            SdkLog.d("warm/hot start not reported: the app went to the background before the first frame")
            armed = true
        }
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = guard {
        lastPreCreated = WeakReference(activity) to uptime()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = guard {
        if (!armed) return@guard
        val now = uptime()
        if (type == null) {
            val pre = lastPreCreated?.takeIf { it.first.get() === activity }?.second
            open(StartType.WARM, pre ?: now, activity)
            lastEventMs = now
        } else if (handoffExpired(now)) {
            drop("the replacement Activity came more than $MAX_HANDOFF_MS ms after the last event")
        } else if (measured?.get() !== activity) {
            // A newer Activity replaces one that has not drawn (a router that finished in onCreate, a rotation, a lock
            // screen): the start goes on with the same anchor.
            if (now - anchorMs > MAX_WARM_HOT_MS) return@guard drop("the replacement Activity came more than $MAX_WARM_HOT_MS ms after the anchor")
            cancelHook()
            measured = WeakReference(activity)
            resumedMs = null
            handoff = false
            lastEventMs = now
        }
    }

    override fun onActivityStarted(activity: Activity) = guard {
        if (armed && type == null) {
            open(StartType.HOT, uptime(), activity)
            lastEventMs = uptime()
        }
    }

    override fun onActivityResumed(activity: Activity) = guard {
        if (!armed) return@guard
        if (handoff && measured == null) {
            // Something resumed that nobody created: the start cannot be attributed to it, and the user may be back much later.
            return@guard drop("an Activity that was not created resumed during the handoff")
        }
        if (measured?.get() !== activity) return@guard
        cancelHook()
        resumedMs = uptime()
        cancelWait = firstFrame.await(activity) { onFrame(activity) }
    }

    override fun onActivityPaused(activity: Activity) = guard { giveUp(activity) }

    override fun onActivityDestroyed(activity: Activity) = guard {
        giveUp(activity)
        if (handoff) lastEventMs = uptime() // the replacement follows the last event, whichever Activity it was
    }

    /** The measured Activity left before its frame (paused, destroyed): the next one created takes the start over. */
    private fun giveUp(activity: Activity) {
        if (!armed || type == null || measured?.get() !== activity) return
        cancelHook()
        measured = null
        resumedMs = null
        handoff = true
        lastEventMs = uptime()
    }

    private fun handoffExpired(now: Long): Boolean = handoff && now - lastEventMs > MAX_HANDOFF_MS

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun open(startType: StartType, anchor: Long, activity: Activity) {
        type = startType
        anchorMs = anchor
        measured = WeakReference(activity)
        resumedMs = null
        handoff = false
    }

    private fun onFrame(activity: Activity) = guard {
        // A callback of an Activity that was handed over, dropped or already reported is stale.
        if (!armed || measured?.get() !== activity) return@guard
        cancelWait = null // it fired: nothing left to cancel
        val frameMs = uptime()
        val duration = frameMs - anchorMs
        val startType = type ?: return@guard
        if (duration !in 0..MAX_WARM_HOT_MS) return@guard drop("duration ${duration}ms is outside 0..$MAX_WARM_HOT_MS")
        val resumed = resumedMs ?: frameMs
        val phases = if (startType == StartType.WARM) warmPhases(anchorMs, resumed, frameMs) else hotPhases(anchorMs, resumed, frameMs)
        val start = AppStart(startType, duration, screenNameOf(activity.javaClass), phases, trampoline = false, anchorUptimeMs = anchorMs)
        disarm()
        try {
            onAppStart(start)
        } catch (t: Throwable) {
            SdkLog.w("reporting the ${startType.wire} start failed", t)
        }
    }

    private fun drop(why: String) {
        SdkLog.d("warm/hot start not reported: $why")
        disarm()
    }

    private fun disarm() {
        clearPending()
        armed = false
    }

    private fun clearPending() {
        cancelHook()
        type = null
        measured = null
        resumedMs = null
        handoff = false
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

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            SdkLog.w("warm/hot start tracking failed", t)
        }
    }

    companion object {
        /** A longer warm or hot "start" is not a start (a stuck screen, a process the user left): dropped. */
        const val MAX_WARM_HOT_MS = 10_000L

        /** After the measured Activity was given up, the next one must show up within this long of the last event. */
        const val MAX_HANDOFF_MS = 2_000L
    }
}
