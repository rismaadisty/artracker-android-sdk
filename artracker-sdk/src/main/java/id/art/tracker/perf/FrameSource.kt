package id.art.tracker.perf

import id.art.tracker.lifecycle.ActivityTracker

/** Where frame statistics come from; a fake in tests. */
internal interface FrameSource {
    fun start()

    fun stop()

    fun drain(): FrameWindow
}

/** Lets the frame source follow the resumed Activity; production adapts the core's `ActivityTracker` hook. */
internal interface WindowListenerRegistry {
    fun add(l: ActivityTracker.WindowListener)

    fun remove(l: ActivityTracker.WindowListener)
}
