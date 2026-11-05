package id.art.tracker.lifecycle

import id.art.tracker.TelemetryItem
import id.art.tracker.core.ItemFactory

/**
 * Single funnel for every `screen_view` (activity, fragment, Compose navigation, manual `ARTracker.screen`).
 * Emits `event`/`screen_view` `{screen, previous, type}`; the same screen reported twice in a row is dropped, so
 * an Activity and its Fragment host (or a config change that re-resumes the same screen) do not double count.
 * `previous` is omitted for the first screen.
 *
 * [onScreenChanged] is the seam for breadcrumbs (Task 15) and replay: it is called with `(previous, current)`
 * for each emitted change, after the item was handed to [emit].
 */
internal class ScreenTracker(
    private val emit: (TelemetryItem) -> Unit,
    private val items: ItemFactory,
    private val onScreenChanged: ((previous: String?, current: String) -> Unit)? = null,
) {
    @Volatile
    var currentScreen: String? = null
        private set

    fun onScreen(name: String, type: String) {
        if (name.isBlank()) return
        val previous: String?
        synchronized(this) {
            previous = currentScreen
            if (previous == name) return
            currentScreen = name
        }
        val attrs = LinkedHashMap<String, Any?>(3)
        attrs["screen"] = name
        if (previous != null) attrs["previous"] = previous
        attrs["type"] = type
        emit(items.event("screen_view", attrs))
        runCatching { onScreenChanged?.invoke(previous, name) }
    }
}
