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
    private class Binding(controller: Any, val detach: () -> Unit) {
        val ref = WeakReference(controller)
    }

    private val bindings = ArrayList<Binding>()

    /** Registers a listener on [navController] unless it is not a NavController or is already tracked. */
    @Synchronized
    fun track(navController: Any) {
        if (!ClassAvailability.navigation) {
            SdkLog.w("trackNavController() ignored: androidx.navigation is not on the classpath")
            return
        }
        bindings.removeAll { it.ref.get() == null }
        if (bindings.any { it.ref.get() === navController }) return
        val detach = NavBinder.bind(navController, screens) ?: return
        bindings.add(Binding(navController, detach))
    }

    /** Removes every listener (SDK shutdown / re-init). */
    @Synchronized
    fun clear() {
        bindings.forEach { runCatching(it.detach) }
        bindings.clear()
    }
}

/** The only place that touches `androidx.navigation` classes. */
internal object NavBinder {
    /** Number of times this object was entered. Lets tests prove it is never touched without the dependency. */
    internal val bindCalls = AtomicInteger(0)

    /** Returns a detach function, or null when [navController] is not a `NavController`. */
    fun bind(navController: Any, screens: ScreenTracker): (() -> Unit)? {
        bindCalls.incrementAndGet()
        val controller = navController as? NavController ?: run {
            SdkLog.w("trackNavController() ignored: argument is not a NavController")
            return null
        }
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            runCatching { screens.onScreen(destinationName(destination), "compose") }
        }
        controller.addOnDestinationChangedListener(listener)
        return { controller.removeOnDestinationChangedListener(listener) }
    }

    private fun destinationName(d: NavDestination): String =
        d.route ?: d.label?.toString() ?: d.id.toString()
}
