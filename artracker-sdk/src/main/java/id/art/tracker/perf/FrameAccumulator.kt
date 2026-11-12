package id.art.tracker.perf

/** Frames seen since the last drain: [jank] frames took more than 2 vsync periods, [frozen] more than 700 ms. */
internal data class FrameWindow(val frames: Int, val jank: Int, val frozen: Int, val refreshHz: Float)

private const val DEFAULT_REFRESH_HZ = 60f
private const val FROZEN_NS = 700_000_000L
private const val NS_PER_SECOND = 1e9

/** Thread-safe: fed from the frame-metrics thread, drained from the sampling loop. */
internal class FrameAccumulator {
    private var frames = 0
    private var jank = 0
    private var frozen = 0
    private var refreshHz = DEFAULT_REFRESH_HZ

    @Synchronized
    fun onFrame(totalDurationNs: Long, refreshHz: Float) {
        val hz = if (refreshHz > 0f) refreshHz else DEFAULT_REFRESH_HZ
        this.refreshHz = hz
        frames++
        if (totalDurationNs > 2 * (NS_PER_SECOND / hz)) jank++
        if (totalDurationNs > FROZEN_NS) frozen++
    }

    /** Returns what was accumulated and resets the counters; the refresh rate is the last one seen. */
    @Synchronized
    fun drain(): FrameWindow {
        val window = FrameWindow(frames, jank, frozen, refreshHz)
        frames = 0
        jank = 0
        frozen = 0
        return window
    }
}

/** Frames per second over [elapsedMs]; null with fewer than 2 frames (an idle screen is not 0 fps) or no time. */
internal fun FrameWindow.fps(elapsedMs: Long): Double? {
    if (frames < 2 || elapsedMs <= 0) return null
    return frames * 1000.0 / elapsedMs
}
