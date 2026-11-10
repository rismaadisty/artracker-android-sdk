package id.art.tracker.replay.capture

import android.os.Handler
import android.view.View
import android.view.ViewTreeObserver
import id.art.tracker.core.Clock
import id.art.tracker.core.SdkLog
import java.util.WeakHashMap

/**
 * Rate control for replay frame capture (ARCHITECTURE section 6.3).
 *
 * - `OnDrawListener` callbacks only flip a dirty flag; no work or allocation happens per draw.
 * - [requestedFps] outside 0.5..[maxFps] (4.0 unless the caller allows more; or non-finite) is clamped (NaN/Infinity -> 1.0).
 * - Every `1000 / currentFps` ms a tick runs [capture] only if the flag is dirty.
 * - [requestSoon] schedules ONE extra capture (after ACTION_UP / screen change), regardless of dirty.
 * - Watchdog: [budgetMs] exceeded on [SLOW_STREAK] consecutive captures (or [capture] throwing that many
 *   times in a row) halves the fps down to [MIN_FPS]; [RECOVERY_MS] without a slow capture restores
 *   [baseFps] (checked on every tick).
 * - [stop] removes every pending callback and every draw listener, so a stopped scheduler costs nothing.
 *
 * Capture duration is measured with the injected [Clock] (wall clock; it only offers `nowMs`), so a
 * system clock jump during a single capture can misclassify that one capture. Tests advance a fake clock
 * inside [capture].
 *
 * Threading: main thread only. [handler] is the main-looper handler in production. No method is
 * synchronised.
 */
internal class FrameScheduler(
    private val handler: Handler,
    private val clock: Clock,
    requestedFps: Double,
    private val budgetMs: Long = 8,
    /** Upper bound of the configured rate: 4.0 for wireframes, 10.0 for screenshots. */
    private val maxFps: Double = MAX_FPS,
    private val capture: () -> Unit,
) {
    /**
     * The configured rate forced into [MIN_FPS]..[MAX_FPS]: NaN means 1.0, +-Infinity too, anything else is clamped.
     * Without it a NaN or 0 would give a 1 ms tick loop on the main thread (and `maxOf(.., NaN)` never recovers).
     */
    private val baseFps: Double =
        if (requestedFps.isFinite()) requestedFps.coerceIn(MIN_FPS, maxFps) else DEFAULT_FPS

    /** Current fps; lowered by the watchdog, restored to [baseFps] after [RECOVERY_MS]. */
    var currentFps: Double = baseFps
        private set

    private var started = false
    private var dirty = false
    private var soonPending = false
    private var consecutiveSlow = 0
    private var consecutiveFailures = 0
    private var lastSlowAtMs = 0L
    private var externalCap: Double? = null

    // Weak keys: a forgotten root never keeps a View/Activity alive. Values capture only `this`.
    private val listeners = WeakHashMap<View, ViewTreeObserver.OnDrawListener>()

    private val tickRunnable = Runnable { onTick() }
    private val soonRunnable = Runnable { onSoon() }

    /** Starts ticking. Idempotent. */
    fun start() {
        if (started) return
        started = true
        dirty = false
        consecutiveSlow = 0
        consecutiveFailures = 0
        scheduleTick()
    }

    /** Cancels every pending callback, detaches every draw listener and clears state. Idempotent. */
    fun stop() {
        started = false
        dirty = false
        soonPending = false
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(soonRunnable)
        val roots = listeners.keys.toList()
        for (root in roots) detach(root)
        listeners.clear()
        // Stopping also ends any degradation; the next start() begins at baseFps.
        currentFps = baseFps
        externalCap = null
    }

    /** Flags that the screen changed since the last capture. Ignored while stopped. */
    fun markDirty() {
        if (!started) return
        dirty = true
    }

    /** Schedules a single extra capture after [delayMs]; coalesced, forced even if not dirty. */
    fun requestSoon(delayMs: Long = 100) {
        if (!started || soonPending) return
        soonPending = true
        handler.postDelayed(soonRunnable, delayMs)
    }

    /** Registers a draw listener on [root] (idempotent per root). Ignored while stopped. */
    fun attach(root: View) {
        if (!started) {
            SdkLog.d("FrameScheduler.attach ignored: not started")
            return
        }
        if (listeners.containsKey(root)) return
        runCatching {
            val observer = root.viewTreeObserver
            if (!observer.isAlive) return
            val listener = ViewTreeObserver.OnDrawListener { markDirty() }
            observer.addOnDrawListener(listener)
            listeners[root] = listener
        }.onFailure { SdkLog.w("FrameScheduler.attach failed", it) }
    }

    /** Removes the draw listener from [root]; safe for unknown roots and dead observers. */
    fun detach(root: View) {
        val listener = listeners.remove(root) ?: return
        runCatching {
            // Re-fetch: the observer seen at attach time may have been replaced or killed since.
            val observer = root.viewTreeObserver
            if (observer.isAlive) observer.removeOnDrawListener(listener)
        }.onFailure { SdkLog.w("FrameScheduler.detach failed", it) }
    }

    /** An outside limit (the screenshot byte budget); null removes it. Lowers the tick rate, never raises it. */
    fun capFps(limit: Double?) {
        externalCap = limit?.takeIf { it.isFinite() && it > 0.0 }?.coerceAtLeast(MIN_FPS)
        if (started) scheduleTick() // the tick already posted was timed with the old rate
    }

    private fun periodMs(): Long {
        val fps = externalCap?.let { minOf(it, currentFps) } ?: currentFps
        return (1000.0 / fps).toLong().coerceAtLeast(1L)
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, periodMs())
    }

    private fun onTick() {
        if (!started) return
        maybeRecover()
        if (dirty) runCapture()
        if (started) scheduleTick() // capture() could in theory have called stop()
    }

    private fun onSoon() {
        soonPending = false
        if (!started) return
        runCapture()
    }

    private fun maybeRecover() {
        if (currentFps != baseFps && clock.nowMs() - lastSlowAtMs >= RECOVERY_MS) {
            currentFps = baseFps
            consecutiveSlow = 0
            consecutiveFailures = 0
        }
    }

    private fun runCapture() {
        dirty = false
        val startMs = clock.nowMs()
        // Debug builds only (SdkLog.enabled is false otherwise): the capture cost for the perf measurements in PERF.md.
        val startNs = if (SdkLog.enabled) System.nanoTime() else 0L
        try {
            capture()
        } catch (t: Throwable) {
            SdkLog.w("Replay capture failed", t)
            consecutiveSlow = 0
            consecutiveFailures++
            if (consecutiveFailures >= SLOW_STREAK) slowDown()
            return
        }
        consecutiveFailures = 0
        if (SdkLog.enabled) {
            SdkLog.d("replay capture took ${(System.nanoTime() - startNs) / 1_000} us (budget $budgetMs ms, fps $currentFps)")
        }
        val elapsed = clock.nowMs() - startMs
        if (elapsed > budgetMs) {
            lastSlowAtMs = clock.nowMs()
            consecutiveSlow++
            if (consecutiveSlow >= SLOW_STREAK) slowDown()
        } else {
            consecutiveSlow = 0
        }
    }

    private fun slowDown() {
        currentFps = maxOf(MIN_FPS, currentFps / 2)
        consecutiveSlow = 0
        consecutiveFailures = 0
        lastSlowAtMs = clock.nowMs()
        SdkLog.d("Replay watchdog: fps lowered to $currentFps")
    }

    private companion object {
        const val SLOW_STREAK = 3
        const val MIN_FPS = 0.5
        const val MAX_FPS = 4.0
        const val DEFAULT_FPS = 1.0
        const val RECOVERY_MS = 30_000L
    }
}
