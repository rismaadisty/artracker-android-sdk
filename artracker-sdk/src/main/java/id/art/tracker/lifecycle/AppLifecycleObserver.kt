package id.art.tracker.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import id.art.tracker.core.SdkLog

/**
 * Observes `ProcessLifecycleOwner`: [onForeground] when the first activity starts, [onBackground] when the last
 * one stopped (after the library's own 700 ms debounce for configuration changes). Never throws.
 */
internal class AppLifecycleObserver(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        runCatching(onForeground).onFailure { SdkLog.w("foreground hook failed", it) }
    }

    override fun onStop(owner: LifecycleOwner) {
        runCatching(onBackground).onFailure { SdkLog.w("background hook failed", it) }
    }
}
