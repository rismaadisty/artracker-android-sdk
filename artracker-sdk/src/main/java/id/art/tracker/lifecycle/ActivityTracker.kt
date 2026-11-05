package id.art.tracker.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import id.art.tracker.core.ClassAvailability
import id.art.tracker.core.SdkLog

/**
 * Reports a `screen_view` (type `activity`) whenever an Activity resumes, and, when the optional Fragment
 * dependency is present, registers [FragmentSupport] on every `FragmentActivity`.
 *
 * Fragment classes are never referenced from this class (not even in an `is` check): everything that needs them
 * lives in `FragmentTracker.kt`, which is only loaded after [ClassAvailability.fragment] said yes.
 */
internal class ActivityTracker(
    private val screens: ScreenTracker,
    private val listener: WindowListener?,
) : Application.ActivityLifecycleCallbacks {

    /** Window-level hook for session replay (Task 23): the root window changed or went away. */
    interface WindowListener {
        fun onActivityResumed(a: Activity)

        fun onActivityPaused(a: Activity)
    }

    private val fragmentDetachers = HashMap<Activity, () -> Unit>()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!ClassAvailability.fragment) return
        runCatching {
            val detach = FragmentSupport.register(activity, screens) ?: return@runCatching
            synchronized(fragmentDetachers) { fragmentDetachers[activity] = detach }
        }.onFailure { SdkLog.w("fragment tracking failed", it) }
    }

    override fun onActivityResumed(activity: Activity) {
        runCatching { screens.onScreen(screenNameOf(activity.javaClass), "activity") }
            .onFailure { SdkLog.w("screen tracking failed", it) }
        runCatching { listener?.onActivityResumed(activity) }
    }

    override fun onActivityPaused(activity: Activity) {
        runCatching { listener?.onActivityPaused(activity) }
    }

    override fun onActivityDestroyed(activity: Activity) {
        synchronized(fragmentDetachers) { fragmentDetachers.remove(activity) }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** Drops the fragment callbacks of activities that are still alive (SDK shutdown). */
    fun detachAll() {
        val all = synchronized(fragmentDetachers) {
            fragmentDetachers.values.toList().also { fragmentDetachers.clear() }
        }
        all.forEach { runCatching(it) }
    }
}
