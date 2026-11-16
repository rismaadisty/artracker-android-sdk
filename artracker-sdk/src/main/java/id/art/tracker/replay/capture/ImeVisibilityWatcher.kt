package id.art.tracker.replay.capture

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap

/**
 * Reports when the soft keyboard of a window is shown or hidden, for the replay "keyboard" trigger.
 *
 * Observes with one `OnGlobalLayoutListener` per watched view (a layout pass happens when the keyboard resizes the
 * window; there is no per-frame work) and reads [visibility] there: [onChanged] only fires when the value differs from
 * the last known one. A view whose visibility is unknown (`null`, e.g. not attached yet) is ignored. The initial
 * state at [attach] is not a change. Main thread only.
 */
internal class ImeVisibilityWatcher(
    private val visibility: (View) -> Boolean? = ::imeVisible,
    private val onChanged: (Boolean) -> Unit,
) {
    private class Watch(val listener: ViewTreeObserver.OnGlobalLayoutListener)

    // Weak keys: a forgotten window never stays alive because of us.
    private val watches = WeakHashMap<View, Watch>()

    fun attach(view: View) {
        if (watches.containsKey(view)) return
        var last = visibility(view) ?: false
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val now = visibility(view)
            if (now != null && now != last) {
                last = now
                onChanged(now)
            }
        }
        runCatching {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.addOnGlobalLayoutListener(listener)
                watches[view] = Watch(listener)
            }
        }
    }

    fun detach(view: View) {
        val watch = watches.remove(view) ?: return
        runCatching {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(watch.listener)
        }
    }

    fun detachAll() {
        for (view in watches.keys.toList()) detach(view)
    }

    private companion object {
        fun imeVisible(view: View): Boolean? =
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())
    }
}
