package id.art.tracker.lifecycle

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import id.art.tracker.core.ClassAvailability
import id.art.tracker.core.SdkLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks Jetpack Navigation (Compose or fragment based) destinations as `screen_view` events of type `compose`.
 *
 * This class takes the controller as `Any` and does not mention a Navigation type, so it can be loaded without the
 * optional dependency. The casts live in [NavBinder], which is only entered when [ClassAvailability.navigation].
 */
internal class NavTracker(private val screens: ScreenTracker) {
    /**
     * Holds the controller and its listener WEAKLY: this tracker lives as long as the SDK, while a controller
     * (which references an Activity context) must be collectable once the app drops it. The controller itself
     * keeps the listener alive for as long as it exists.
     */
    private class Binding(controller: Any, listener: Any) {
        val controllerRef = WeakReference(controller)
        val listenerRef = WeakReference(listener)
    }

    private val bindings = ArrayList<Binding>()

    /** Registers a listener on [navController] unless it is not a NavController or is already tracked. */
    @Synchronized
    fun track(navController: Any) {
        if (!ClassAvailability.navigation) {
            SdkLog.w("trackNavController() ignored: androidx.navigation is not on the classpath")
            return
        }
        bindings.removeAll { it.controllerRef.get() == null }
        if (bindings.any { it.controllerRef.get() === navController }) return
        val listener = NavBinder.bind(navController, screens) ?: return
        bindings.add(Binding(navController, listener))
    }

    /** Removes every listener (SDK shutdown / re-init). */
    @Synchronized
    fun clear() {
        bindings.forEach { b ->
            val controller = b.controllerRef.get() ?: return@forEach
            val listener = b.listenerRef.get() ?: return@forEach
            runCatching { NavBinder.unbind(controller, listener) }
        }
        bindings.clear()
    }
}

/** The only place that touches `androidx.navigation` classes. */
internal object NavBinder {
    /** Number of times this object was entered. Lets tests prove it is never touched without the dependency. */
    internal val bindCalls = AtomicInteger(0)

    /** Returns the registered listener (opaque, to be passed to [unbind]), or null when not a `NavController`. */
    fun bind(navController: Any, screens: ScreenTracker): Any? {
        bindCalls.incrementAndGet()
        val controller = navController as? NavController ?: run {
            SdkLog.w("trackNavController() ignored: argument is not a NavController")
            return null
        }
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            runCatching { screens.onScreen(destinationName(destination), "compose") }
        }
        controller.addOnDestinationChangedListener(listener)
        return listener
    }

    /** Removes a listener returned by [bind]. The caller must not keep [navController] strongly referenced. */
    fun unbind(navController: Any, listener: Any) {
        (navController as? NavController)?.removeOnDestinationChangedListener(
            listener as NavController.OnDestinationChangedListener,
        )
    }

    private fun destinationName(d: NavDestination): String =
        d.route ?: d.label?.toString() ?: d.id.toString()
}
