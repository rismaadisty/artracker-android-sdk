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

    private var currentType: String = "manual"

    @Synchronized
    fun onScreen(name: String, type: String) {
        if (name.isBlank()) return
        val previous = currentScreen
        if (previous == name) return
        // Build the item BEFORE recording the new screen: creating it may start a session (hard-cap rotation),
        // whose listener calls onSessionStarted(), which must still see the old screen.
        val attrs = LinkedHashMap<String, Any?>(3)
        attrs["screen"] = name
        if (previous != null) attrs["previous"] = previous
        attrs["type"] = type
        val item = items.event("screen_view", attrs)
        currentScreen = name
        currentType = type
        emit(item)
        runCatching { onScreenChanged?.invoke(previous, name) }
    }

    /**
     * A new session has begun (idle timeout, hard cap, logout): the screen the user is still looking at is
     * reported again, in the new session and without `previous`, because Activities and Compose navigation do
     * not re-resume on their own. [sessionItems] must stamp the NEW session (the caller is inside the session
     * listener). Does nothing before the first screen, and within one session duplicates stay suppressed.
     * Not reported to [onScreenChanged]: the visible screen did not change.
     */
    @Synchronized
    fun onSessionStarted(sessionItems: ItemFactory) {
        val screen = currentScreen ?: return
        emit(sessionItems.event("screen_view", mapOf("screen" to screen, "type" to currentType)))
    }
}
