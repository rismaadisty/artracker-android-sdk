package id.art.tracker.perf

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import id.art.tracker.core.SdkLog

/**
 * Reports `TRIM_MEMORY_RUNNING_LOW` (10) and `TRIM_MEMORY_RUNNING_CRITICAL` (15) while the app is in the foreground.
 * Every other level (UI hidden, background, complete) says the app is not on screen or is already being trimmed, and is
 * ignored. [emit] gets the trim level.
 */
internal class MemoryWarningCallbacks(
    private val isForeground: () -> Boolean,
    private val emit: (Int) -> Unit,
) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            return
        }
        runCatching { if (isForeground()) emit(level) }.onFailure { SdkLog.w("memory warning failed", it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Superseded by onTrimMemory", ReplaceWith("onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)"))
    override fun onLowMemory() = Unit
}
