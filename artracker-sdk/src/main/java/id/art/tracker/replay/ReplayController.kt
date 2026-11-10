package id.art.tracker.replay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import androidx.annotation.VisibleForTesting
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.SdkLog
import id.art.tracker.effectiveFps
import id.art.tracker.preset
import id.art.tracker.lower
import id.art.tracker.lifecycle.ActivityTracker
import id.art.tracker.replay.capture.FrameScheduler
import id.art.tracker.replay.capture.RootViewLocator
import id.art.tracker.replay.screenshot.ByteBudgetGovernor
import id.art.tracker.replay.screenshot.RawFrame
import id.art.tracker.replay.screenshot.ScreenChunker
import id.art.tracker.replay.screenshot.ScreenTouch
import id.art.tracker.replay.screenshot.ScreenshotKit
import id.art.tracker.replay.touch.TouchKind
import id.art.tracker.replay.touch.TouchRecorder
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Session replay recording (ARCHITECTURE.md section 6): real screenshots of the app, masked on the device.
 *
 * Recording runs only while ALL hold: the app is in the foreground, the SDK is not disabled (opt-out / 401
 * kill-switch), [stop] was not called after the last [start], the server has not answered `403 screenshot_disabled`,
 * and either `start(manual = true)` was called or `config.replay.enabled && sampler(sessionId, config.replay.sampleRate)`
 * for the CURRENT session (re-evaluated at every new session).
 *
 * Threading:
 * - Main thread: every public method hops there ([onMain]); it owns the lifecycle state, the [scheduler], the touch
 *   wrappers and the capture itself ([captureFrame]: [RootViewLocator] + [ScreenshotKit.capturer] composite the
 *   windows and collect the mask regions, read-only view access).
 * - One serial background context ([processing], `Dispatchers.Default.limitedParallelism(1)` in production) owns the
 *   [ScreenshotKit.encoder] and [ScreenshotKit.chunker]: it masks, scales, compresses, deduplicates and chunks the
 *   frames ([ScreenChunker]), under a byte budget ([ByteBudgetGovernor]). Frames use latest-frame-wins: at most one
 *   waits; a newer frame replaces it.
 *
 * Output: a frame at the first capture, on screen change (the raw name is only used to force the frame), after a
 * session change or a restart, and otherwise whenever the picture changed; touches ride on the same chunk. A closed
 * chunk goes to `core.replaySink` (stamped with the session it was recorded in), [FLUSH_INTERVAL_MS] ticks close the
 * open chunk once its window is due, and it is closed when recording stops.
 *
 * Privacy: the only text in the output is the `href` built by [ReplayHref] (redacted, path ids masked, URL-safe); the
 * controller never reads view text itself, and sensitive regions are painted over before the JPEG exists.
 *
 * Memory: one pending frame and the open chunk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReplayController(
    private val core: ARTrackerCore,
    private val screenshot: ScreenshotKit,
    private val scheduler: FrameScheduler,
    private val mainHandler: Handler,
    private val sampler: (String, Double) -> Boolean = Sampling::isSampled,
    processing: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : ActivityTracker.WindowListener {

    private val scope = CoroutineScope(
        SupervisorJob() + processing + CoroutineExceptionHandler { _, t -> SdkLog.w("replay processing failed", t) },
    )
    private val replayConfig = core.config.replay
    private val baseFps = replayConfig.effectiveFps()

    // region main-thread state
    private var manualRequested = false
    private var userStopped = false
    private var screenshotDisabled = false
    private var foreground = core.isForeground
    private var running = false
    private var recordingSessionId: String? = null
    private var forceFullNext = false
    private var currentActivity: WeakReference<Activity>? = null
    private val attachedRoots = ArrayList<WeakReference<View>>()
    private val wrappedWindows = ArrayList<WeakReference<Window>>()
    private val flushRunnable = Runnable { onFlushTick() }

    // Last screen name -> its sanitized href: the redactor regexes must not run on the main thread every frame.
    private var hrefScreen: String? = null
    private var hrefValue: String? = null
    private var hrefCached = false

    /** Builds the href for a raw screen name; replaced by tests to count invocations. */
    @VisibleForTesting
    internal var hrefBuilder: (String?) -> String = ReplayHref::of
    // endregion

    // region hand-off between the threads
    private class ShotJob(
        val raw: RawFrame,
        val href: String,
        val density: Float,
        val sessionId: String,
        val force: Boolean,
    )

    private val pendingShot = AtomicReference<ShotJob?>(null)
    private val shotJobQueued = AtomicBoolean(false)
    @Volatile
    private var governor: ByteBudgetGovernor? = null

    /** Clock time of the last forced frame (the periodic keyframe check); written by the main thread. */
    @Volatile
    private var lastFullAtMs = 0L
    // endregion

    // region processing-context state
    private var shotSessionId: String? = null
    private var lastGovernorCap: Double? = null
    // endregion

    /** Main-thread view of whether recording is active (tests and diagnostics). */
    @VisibleForTesting
    internal val isRunning: Boolean get() = running

    // region public API (any thread)

    /** Requests recording; [manual] (`ARTracker.startReplay()`) bypasses `enabled` and sampling. */
    fun start(manual: Boolean) = onMain {
        if (manual) {
            manualRequested = true
            userStopped = false
        }
        evaluate()
    }

    /** `ARTracker.stopReplay()`: flushes what was recorded and removes every listener, callback and wrapper. */
    fun stop() = onMain {
        manualRequested = false
        userStopped = true
        halt(flushPipeline = false)
    }

    fun onAppBackground() = onMain {
        foreground = false
        halt(flushPipeline = true)
    }

    fun onAppForeground() = onMain {
        foreground = true
        forceFullNext = true
        evaluate()
    }

    /**
     * The server answered `403 screenshot_disabled` (the upload side already dropped the queued screenshot chunks):
     * recording stops for this process and what is still open is dropped instead of uploaded. Logs once.
     */
    fun onScreenshotDisabled() = onMain {
        if (screenshotDisabled) return@onMain
        screenshotDisabled = true
        SdkLog.w("screenshot replay was switched off for this project; recording stopped")
        halt(flushPipeline = false, discard = true)
    }

    /** Opt-out / kill-switch changed. */
    fun onDisabledChanged() = onMain { evaluate() }

    /**
     * A session started (from the session listener, on any thread, possibly inside another SDK call): always
     * posted, never run inline. Sampling is re-evaluated for the new id and the next frame is a full snapshot.
     */
    fun onSessionStarted() {
        post {
            forceFullNext = true
            evaluate()
        }
    }

    /** The visible screen changed (called inside the screen tracker's lock: only posts). */
    fun onScreenChanged() {
        post {
            if (!running) return@post
            forceFullNext = true
            scheduler.requestSoon()
        }
    }

    override fun onActivityResumed(a: Activity) = onMain {
        currentActivity = WeakReference(a)
        foreground = true
        if (running) {
            forceFullNext = true
            reattachRoots()
            wrapTouch(a)
            scheduler.requestSoon()
        } else {
            evaluate()
        }
    }

    override fun onActivityPaused(a: Activity) = onMain {
        runCatching { a.window }.getOrNull()?.let { window ->
            unwrapTouch(window)
            runCatching { window.decorView }.getOrNull()?.let(::detachRoot)
        }
        if (currentActivity?.get() === a) currentActivity = null
    }

    /** SDK shutdown: stops recording and cancels the background context. */
    fun shutdown() {
        onMain { halt(flushPipeline = false) }
        scope.cancel()
    }

    // endregion

    // region main thread: lifecycle

    private fun shouldRecord(sessionId: String): Boolean =
        !userStopped && !screenshotDisabled && (manualRequested || (replayConfig.enabled && sampler(sessionId, replayConfig.sampleRate)))

    private fun evaluate() {
        if (!foreground || core.isDisabled()) {
            halt(flushPipeline = false)
            return
        }
        val sessionId = core.session.currentId()
        if (!shouldRecord(sessionId)) {
            halt(flushPipeline = false)
            return
        }
        if (!running) {
            begin(sessionId)
        } else if (sessionId != recordingSessionId) {
            recordingSessionId = sessionId
            forceFullNext = true
            scheduler.requestSoon()
        }
    }

    private fun begin(sessionId: String) {
        running = true
        recordingSessionId = sessionId
        forceFullNext = true
        lastFullAtMs = core.clock.nowMs()
        governor = screenshot.governorFactory(baseFps, replayConfig.quality)
        lastGovernorCap = null
        applyFpsCaps()
        scheduler.start() // T21 contract: start BEFORE attach
        reattachRoots()
        currentActivity?.get()?.let(::wrapTouch)
        mainHandler.removeCallbacks(flushRunnable)
        mainHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
        scheduler.requestSoon()
    }

    /** The capture rate is capped by the byte budget (the governor halves it while frames run over budget). */
    private fun applyFpsCaps() {
        scheduler.capFps(governor?.fpsCap)
    }

    /** Stops all main-thread activity and flushes the open chunk. Nothing stays posted or attached. */
    private fun halt(flushPipeline: Boolean, discard: Boolean = false) {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(flushRunnable)
        scheduler.stop() // removes its callbacks and every draw listener
        attachedRoots.clear()
        unwrapAllTouch()
        pendingShot.getAndSet(null)?.raw?.release()
        submit {
            if (discard) {
                screenshot.chunker.discard()
                screenshot.encoder.resetDuplicates()
            } else {
                closeScreenChunk()
            }
            if (flushPipeline) core.pipeline.flushNow()
        }
    }

    private fun onFlushTick() {
        if (!running) return
        if (core.isDisabled()) {
            halt(flushPipeline = false)
            return
        }
        submit {
            if (core.isDisabled()) screenshot.chunker.discard() else screenshot.chunker.closeIfDue()
        }
        if (core.clock.nowMs() - lastFullAtMs >= CHECKOUT_MS) {
            forceFullNext = true
            scheduler.requestSoon()
        }
        mainHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    private fun reattachRoots() {
        // Re-attach rather than trusting attach()'s idempotence: a root may have a new ViewTreeObserver.
        for (ref in attachedRoots) ref.get()?.let(scheduler::detach)
        attachedRoots.clear()
        for (root in RootViewLocator.roots(currentActivity?.get())) attachRoot(root)
    }

    private fun attachRoot(root: View) {
        attachedRoots.removeAll { it.get() == null }
        if (attachedRoots.any { it.get() === root }) return
        scheduler.attach(root)
        attachedRoots.add(WeakReference(root))
    }

    private fun detachRoot(root: View) {
        scheduler.detach(root)
        attachedRoots.removeAll { it.get() == null || it.get() === root }
    }

    private fun wrapTouch(activity: Activity) {
        val window = runCatching { activity.window }.getOrNull() ?: return
        val density = runCatching { activity.resources.displayMetrics.density }.getOrDefault(1f).takeIf { it > 0f } ?: 1f
        TouchRecorder.wrap(window) { kind, x, y -> onTouch(kind, x, y, density) } ?: return
        if (wrappedWindows.none { it.get() === window }) wrappedWindows.add(WeakReference(window))
    }

    private fun unwrapTouch(window: Window) {
        TouchRecorder.unwrap(window)
        wrappedWindows.removeAll { it.get() == null || it.get() === window }
    }

    private fun unwrapAllTouch() {
        for (ref in wrappedWindows) ref.get()?.let(TouchRecorder::unwrap)
        wrappedWindows.clear()
    }

    // endregion

    // region main thread: capture

    /** FrameScheduler capture callback: composites every root window into one masked frame and hands it to the processor. */
    internal fun captureFrame() {
        if (!running) return
        if (core.isDisabled()) {
            halt(flushPipeline = false)
            return
        }
        val sessionId = recordingSessionId ?: return
        val activity = currentActivity?.get()
        val roots = RootViewLocator.roots(activity)
        if (roots.isEmpty()) return
        for (root in roots) attachRoot(root) // new dialog / popup windows
        val screen = core.screens.currentScreen
        val href = hrefFor(screen)
        val force = forceFullNext
        forceFullNext = false
        val density = roots[0].resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        screenshot.capturer.capture(roots, activity?.window, core.clock.nowMs()) { raw ->
            if (raw == null) {
                // Busy or a mask collection that failed: nothing is sent, and a forced frame stays forced.
                if (force) post { forceFullNext = true }
                return@capture
            }
            submitShot(ShotJob(raw, href, density, sessionId, force))
        }
    }

    /** [ReplayHref.of] memoised for the most recent raw name (main thread only). */
    private fun hrefFor(screen: String?): String {
        if (hrefCached && hrefScreen == screen) return hrefValue!!
        val href = hrefBuilder(screen)
        hrefScreen = screen
        hrefValue = href
        hrefCached = true
        return href
    }

    private fun onTouch(kind: TouchKind, rawX: Float, rawY: Float, density: Float) {
        if (!running) return
        val sessionId = recordingSessionId ?: return
        val x = (rawX / density).roundToInt()
        val y = (rawY / density).roundToInt()
        val ts = core.clock.nowMs()
        submit { screenshot.chunker.addTouch(sessionId, ScreenTouch(ts, x, y, kind)) }
        if (kind == TouchKind.UP && scheduler.currentFps >= baseFps) scheduler.requestSoon()
    }

    // endregion

    // region processing context

    private fun submit(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                SdkLog.w("replay processing failed", t)
            }
        }
    }

    private fun submitShot(job: ShotJob) {
        pendingShot.getAndSet(job)?.raw?.release() // latest frame wins
        if (shotJobQueued.compareAndSet(false, true)) {
            submit {
                shotJobQueued.set(false)
                pendingShot.getAndSet(null)?.let(::processShot)
            }
        }
    }

    /** Masks, scales, compresses and chunks one screenshot; always gives the buffer back. */
    private fun processShot(job: ShotJob) {
        try {
            if (job.sessionId != shotSessionId) {
                screenshot.chunker.close()
                shotSessionId = job.sessionId
                screenshot.encoder.resetDuplicates()
            }
            if (core.isDisabled()) {
                screenshot.chunker.discard()
                return
            }
            val gov = governor
            val quality = gov?.quality ?: replayConfig.quality
            var frame = screenshot.encoder.encode(job.raw, quality.preset(), job.href, job.density, job.force) ?: return
            var added = screenshot.chunker.addFrame(job.sessionId, frame)
            if (added == ScreenChunker.Add.TOO_LARGE) {
                // A frame that cannot fit a chunk: once more one preset lower, else dropped.
                val lower = quality.lower()
                if (lower != null) {
                    frame = screenshot.encoder.encode(job.raw, lower.preset(), job.href, job.density, true) ?: return
                    added = screenshot.chunker.addFrame(job.sessionId, frame)
                }
                if (added == ScreenChunker.Add.TOO_LARGE) {
                    SdkLog.w("screenshot frame too large for a chunk; dropped")
                    return
                }
            }
            gov?.record(core.clock.nowMs(), frame.jpeg.size.toLong())
            val cap = gov?.fpsCap
            if (cap != lastGovernorCap) {
                lastGovernorCap = cap
                post { applyFpsCaps() }
            }
        } finally {
            job.raw.release()
        }
    }

    private fun closeScreenChunk() {
        if (core.isDisabled()) screenshot.chunker.discard() else screenshot.chunker.close()
        screenshot.encoder.resetDuplicates()
    }

    // endregion

    // region threading helpers

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === mainHandler.looper) {
            runCatching(block).onFailure { SdkLog.w("replay main-thread work failed", it) }
        } else {
            post(block)
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post { runCatching(block).onFailure { SdkLog.w("replay main-thread work failed", it) } }
    }

    // endregion

    /** An error was logged: the open replay batch (or the next one) is flagged `has_error`. */
    internal fun noteError() {
        submit { screenshot.chunker.markError() }
    }

    /** Test hook: closes the open chunk now (the timer closes it only after its window). */
    @VisibleForTesting
    internal fun flushChunks() = onMain {
        submit { closeScreenChunk() }
    }

    /** Test hook: suspends until everything submitted so far has been processed. */
    @VisibleForTesting
    internal suspend fun awaitIdle() {
        scope.launch { }.join()
    }

    companion object {
        const val FLUSH_INTERVAL_MS: Long = 5_000
        const val CHECKOUT_MS: Long = 120_000
        private const val BUDGET_MS = 16L
        private const val MAX_FPS = 10.0

        /** Wires a [FrameScheduler] whose capture callback is the new controller's [captureFrame]. */
        fun create(
            core: ARTrackerCore,
            screenshot: ScreenshotKit,
            mainHandler: Handler,
            sampler: (String, Double) -> Boolean = Sampling::isSampled,
            processing: CoroutineDispatcher? = null,
        ): ReplayController {
            var controller: ReplayController? = null
            val scheduler = FrameScheduler(
                mainHandler, core.clock, core.config.replay.effectiveFps(), budgetMs = BUDGET_MS, maxFps = MAX_FPS,
            ) {
                controller?.captureFrame()
            }
            val created = ReplayController(
                core, screenshot, scheduler, mainHandler, sampler,
                processing ?: Dispatchers.Default.limitedParallelism(1),
            )
            controller = created
            return created
        }
    }
}
