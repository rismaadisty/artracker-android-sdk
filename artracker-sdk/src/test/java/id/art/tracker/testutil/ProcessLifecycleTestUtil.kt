package id.art.tracker.testutil

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner

/** Drives the process-wide lifecycle by hand (main thread only). Always [stop] in setUp/tearDown: it is global. */
internal object ProcessLifecycleTestUtil {
    val registry: LifecycleRegistry get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    fun start() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun stop() {
        if (registry.currentState == Lifecycle.State.RESUMED) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }
}
