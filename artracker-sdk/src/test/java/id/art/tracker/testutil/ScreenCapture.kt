package id.art.tracker.testutil

import id.art.tracker.TelemetryItem
import id.art.tracker.core.ItemFactory
import id.art.tracker.lifecycle.ScreenTracker
import java.util.concurrent.CopyOnWriteArrayList

/** A [ScreenTracker] wired to an in-memory list, for tests that do not need the whole core. */
internal class ScreenCapture {
    val emitted = CopyOnWriteArrayList<TelemetryItem>()
    val tracker = ScreenTracker({ emitted += it }, ItemFactory(FakeClock(1_000), { "session" }, { null }))

    /** `screen`/`type` of every emitted `screen_view`, e.g. `"Home:activity"`. */
    fun screens(): List<String> = emitted.filter { it.name == "screen_view" }.map {
        "${it.attributes["screen"]}:${it.attributes["type"]}"
    }
}
