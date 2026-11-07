package id.art.tracker.playground.scenario

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.ConcurrentHashMap

/**
 * [inActivityWindow] is false for composables hosted in another window (Dialog, Popup, bottom sheet): a touch sent to the
 * Activity's window would miss them, and the SDK does not record touches in other windows anyway.
 */
data class TapTarget(val boundsInWindow: Rect, val inActivityWindow: Boolean)

class TapRegistry {
    private val map = ConcurrentHashMap<String, TapTarget>()

    fun register(id: String, target: TapTarget) {
        map[id] = target
    }

    fun unregister(id: String) {
        map.remove(id)
    }

    fun find(id: String): TapTarget? = map[id]

    companion object {
        val Shared = TapRegistry()
    }
}

/** Registers this composable's bounds so a scenario can tap it. Do not use it inside a Dialog or Popup. */
fun Modifier.tapTarget(id: String): Modifier = composed {
    val view = LocalView.current
    DisposableEffect(id) { onDispose { TapRegistry.Shared.unregister(id) } }
    onGloballyPositioned { coordinates ->
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        val inActivity = activity?.window?.decorView === view.rootView
        TapRegistry.Shared.register(id, TapTarget(coordinates.boundsInWindow(), inActivity))
    }
}
