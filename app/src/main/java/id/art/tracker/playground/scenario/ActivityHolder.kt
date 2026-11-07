package id.art.tracker.playground.scenario

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Weak reference to the resumed Activity, for the synthetic touch driver. */
object ActivityHolder : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    val resumed: Activity? get() = ref?.get()

    override fun onActivityResumed(activity: Activity) {
        ref = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
