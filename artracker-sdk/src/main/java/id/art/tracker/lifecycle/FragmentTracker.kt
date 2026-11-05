package id.art.tracker.lifecycle

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything that touches `androidx.fragment` classes is in this file, so that nothing else (notably
 * `ActivityTracker`) has the Fragment library on its class-loading path. This file must only be reached after
 * `ClassAvailability.fragment` returned true; otherwise the JVM would throw `NoClassDefFoundError`.
 */
internal class FragmentTracker(private val screens: ScreenTracker) : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
        runCatching { screens.onScreen(screenNameOf(f.javaClass), "fragment") }
    }
}

/** Entry point used by `ActivityTracker`; it takes and returns only framework / SDK types. */
internal object FragmentSupport {
    /** Number of times this object was entered. Lets tests prove it is never touched without the dependency. */
    internal val registerCalls = AtomicInteger(0)

    /** Returns a detach function, or null when [activity] is not a `FragmentActivity`. */
    fun register(activity: Activity, screens: ScreenTracker): (() -> Unit)? {
        registerCalls.incrementAndGet()
        if (activity !is FragmentActivity) return null
        val callbacks = FragmentTracker(screens)
        val fm = activity.supportFragmentManager
        fm.registerFragmentLifecycleCallbacks(callbacks, true)
        return { fm.unregisterFragmentLifecycleCallbacks(callbacks) }
    }
}
