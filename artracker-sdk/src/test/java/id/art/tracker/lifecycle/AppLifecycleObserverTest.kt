package id.art.tracker.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLifecycleObserverTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun stopThenStart_callsCallbacks() {
        val calls = ArrayList<String>()
        val owner = Owner()
        owner.registry.addObserver(AppLifecycleObserver({ calls += "fg" }, { calls += "bg" }))

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        assertEquals(emptyList<String>(), calls) // CREATED is not foreground
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(listOf("fg"), calls)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertEquals(listOf("fg"), calls)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(listOf("fg", "bg", "fg"), calls)
    }

    @Test
    fun lateObserverOnStartedOwner_getsForegroundOnce() {
        val calls = ArrayList<String>()
        val owner = Owner()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.addObserver(AppLifecycleObserver({ calls += "fg" }, { calls += "bg" }))
        assertEquals(listOf("fg"), calls)
    }

    @Test
    fun throwingCallback_isContained() {
        val owner = Owner()
        owner.registry.addObserver(AppLifecycleObserver({ error("boom") }, { error("boom") }))
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
}
