package id.art.tracker.replay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import androidx.annotation.VisibleForTesting
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.ReplayMode
import id.art.tracker.core.SdkLog
import id.art.tracker.effectiveFps
import id.art.tracker.preset
import id.art.tracker.lower
import id.art.tracker.lifecycle.ActivityTracker
import id.art.tracker.replay.capture.FrameScheduler
import id.art.tracker.replay.capture.RootViewLocator
import id.art.tracker.replay.capture.ViewWalker
import id.art.tracker.replay.diff.WireframeDiffer
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import id.art.tracker.replay.rrweb.RrwebEncoder
import id.art.tracker.replay.screenshot.ByteBudgetGovernor
import id.art.tracker.replay.screenshot.RawFrame
import id.art.tracker.replay.screenshot.ScreenChunker
import id.art.tracker.replay.screenshot.ScreenTouch
import id.art.tracker.replay.screenshot.ScreenshotKit
import id.art.tracker.replay.rrweb.TouchKind
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
 * Session replay recording (ARCHITECTURE.md section 6).
 *
 * Recording runs only while ALL hold: the app is in the foreground, the SDK is not disabled (opt-out / 401
 * kill-switch), [stop] was not called after the last [start], and either `start(manual = true)` was called or
 * `config.replay.enabled && sampler(sessionId, config.replay.sampleRate)` for the CURRENT session (re-evaluated at
 * every new session).
 *
 * Threading:
 * - Main thread: every public method hops there ([onMain]); it owns the lifecycle state, the [scheduler], the touch
 *   wrappers and the capture itself ([captureFrame]: [RootViewLocator] + [walker], read-only view access).
 * - One serial background context ([processing], `Dispatchers.Default.limitedParallelism(1)` in production) owns the
 *   diff base, the [encoder] and the [chunker]: diff, encode and chunk. The [Wireframe] tree is immutable, so it
 *   crosses threads as is. Frames use latest-frame-wins: at most one waits; a newer frame replaces it, and then the
 *   next processed frame is a full snapshot.
 *
 * Output: Meta + FullSnapshot at the first frame, on screen change (raw name, kept in memory only) or size change, every [CHECKOUT_MS], after a
 * dropped frame, after a session change or a restart; otherwise a Mutation when the diff is not empty. Chunks are
 * handed to the [ReplayBatcher] (stamped with the session they were recorded in) every [FLUSH_INTERVAL_MS], at 64 KB,
 * and when recording stops; the batcher merges them into upload batches that go to `core.replaySink`.
 *
 * Privacy: the only text in the output comes from the walker (already masked) and the encoder's fixed labels; the
 * controller adds only the `href`, built by [ReplayHref] (redacted, path ids masked, URL-safe), and never reads view
 * text itself.
 *
 * Screenshot mode (`replay.mode == SCREENSHOT`, opt-in): the effective [mode] is SCREENSHOT only while the server's
 * `screenshot_enabled` is true (the cached answer at start, refreshed at every start). Before the first answer the
 * session records wireframes and switches (forcing a first screenshot) when the answer is "enabled"; a `403
 * screenshot_disabled` or a later "disabled" answer switches back, clears the queued screenshot chunks and forces a
 * full wireframe snapshot. In screenshot mode [captureFrame] composites the windows and collects the mask regions on
 * the main thread ([ScreenshotKit.capturer]); the processing context masks, scales, compresses, deduplicates and
 * chunks them ([ScreenChunker]), under a byte budget ([ByteBudgetGovernor]).
 *
 * Memory: the last processed [Wireframe] (diff base), one pending frame, the open chunk and the open batch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReplayController(
    private val core: ARTrackerCore,
    private val walker: ViewWalker,
    private val encoder: RrwebEncoder,
    private val chunker: ReplayChunker,
    private val scheduler: FrameScheduler,
    private val mainHandler: Handler,
    private val sampler: (String, Double) -> Boolean = Sampling::isSampled,
    processing: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** Present when `replay.mode == SCREENSHOT`; the controller still starts in wireframes until the server allows it. */
    private val screenshot: ScreenshotKit? = null,
    private val remote: ReplayRemote? = null,
    /** Deletes the screenshot chunks still queued for upload (the server switched screenshots off). */
    private val clearScreenshotQueue: () -> Unit = {},
    io: CoroutineDispatcher = Dispatchers.IO,
) : ActivityTracker.WindowListener {

    private class Frame(
        val root: Wireframe,
        /** Sanitized; the only form of the screen name that is ever emitted (in Meta). */
        val href: String,
        /**
         * RAW screen name, in memory only and never emitted: screen changes are detected on it because the
         * sanitized [href] is lossy (non-ASCII names, masked path ids and the length cap can collapse screens).
         */
        val screenKey: String,
        val width: Int,
        val height: Int,
        val ts: Long,
        val sessionId: String,
        val forceFull: Boolean,
    )

    private val scope = CoroutineScope(
        SupervisorJob() + processing + CoroutineExceptionHandler { _, t -> SdkLog.w("replay processing failed", t) },
    )

    /** Only for the blocking `GET /replay/config`; never the processing context. */
    private val ioScope = CoroutineScope(
        SupervisorJob() + io + CoroutineExceptionHandler { _, t -> SdkLog.w("replay config fetch failed", t) },
    )
    private val replayConfig = core.config.replay
    private val baseFps = replayConfig.effectiveFps()

    // region main-thread state
    private var manualRequested = false
    private var userStopped = false
    private var foreground = core.isForeground
    private var running = false
    private var recordingSessionId: String? = null
    private var forceFullNext = false

    /** What is being recorded right now; SCREENSHOT only when configured, available and allowed by the server. */
    private var mode = ReplayMode.WIREFRAME
    private var remoteMaxFps: Double? = null
    private var screenshotDisabledLogged = false
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
    private val pendingFrame = AtomicReference<Frame?>(null)
    private val frameJobQueued = AtomicBoolean(false)
    private val frameDropped = AtomicBoolean(false)

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

    /** Clock time of the last full snapshot; written by the processing context, read on main. */
    @Volatile
    private var lastFullAtMs = 0L
    // endregion

    // region processing-context state
    private var base: Wireframe? = null
    private var baseHref: String? = null
    private var baseScreenKey: String? = null
    private var baseWidth = 0
    private var baseHeight = 0
    private var chunkSessionId: String? = null
    private var forceFullAfterFailure = false
    private var shotSessionId: String? = null
    private var lastGovernorCap: Double? = null
    private val batcher = ReplayBatcher(core.replaySink, core.clock, core.config.environment, { core.identity.userId })
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
     * remember it and, when screenshots are being recorded, fall back to wireframes.
     */
    fun onScreenshotDisabled() = onMain {
        remote?.cache?.markScreenshotDisabled()
        if (mode == ReplayMode.SCREENSHOT) fallBackToWireframes()
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
        ioScope.cancel()
    }

    // endregion

    // region main thread: lifecycle

    private fun shouldRecord(sessionId: String): Boolean =
        !userStopped && (manualRequested || (replayConfig.enabled && sampler(sessionId, replayConfig.sampleRate)))

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
        mode = resolveMode()
        remoteMaxFps = remote?.cache?.maxFps
        governor = if (screenshot != null) screenshot.governorFactory(baseFps, replayConfig.quality) else null
        lastGovernorCap = null
        applyFpsCaps()
        scheduler.start() // T21 contract: start BEFORE attach
        reattachRoots()
        currentActivity?.get()?.let(::wrapTouch)
        mainHandler.removeCallbacks(flushRunnable)
        mainHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
        scheduler.requestSoon()
        refreshRemoteConfig()
    }

    private fun screenshotConfigured(): Boolean = screenshot != null && replayConfig.mode == ReplayMode.SCREENSHOT

    /** Screenshots only when configured AND the cached server answer allows it; unknown means wireframes for now. */
    private fun resolveMode(): ReplayMode =
        if (screenshotConfigured() && remote?.cache?.screenshotEnabled == true) ReplayMode.SCREENSHOT else ReplayMode.WIREFRAME

    /** Asks the server again (every start); the answer arrives on the main thread. */
    private fun refreshRemoteConfig() {
        val source = remote ?: return
        if (!screenshotConfigured()) return
        ioScope.launch {
            val answer = runCatching { source.fetch() }.getOrNull() ?: return@launch
            post { onRemoteConfig(answer) }
        }
    }

    private fun onRemoteConfig(answer: RemoteReplayConfig) {
        remote?.cache?.save(answer)
        remoteMaxFps = answer.maxFps
        if (!running) return
        when {
            answer.screenshotEnabled && mode == ReplayMode.WIREFRAME && screenshotConfigured() -> {
                mode = ReplayMode.SCREENSHOT
                forceFullNext = true // a first screenshot is never suppressed as a duplicate
                submit {
                    // What the wireframe recording holds is closed first: the two formats never share a batch.
                    drainChunks()
                    closeBatch()
                    resetDiffState()
                }
                scheduler.requestSoon()
            }
            !answer.screenshotEnabled && mode == ReplayMode.SCREENSHOT -> fallBackToWireframes()
        }
        applyFpsCaps()
    }

    /** Back to wireframes: queued screenshots are cleared, the next frame is a full snapshot. Logs once. */
    private fun fallBackToWireframes() {
        if (!screenshotDisabledLogged) {
            screenshotDisabledLogged = true
            SdkLog.w("screenshot replay was switched off for this project; recording wireframes instead")
        }
        mode = ReplayMode.WIREFRAME
        forceFullNext = true
        pendingShot.getAndSet(null)?.raw?.release()
        submit {
            screenshot?.chunker?.discard()
            screenshot?.encoder?.resetDuplicates()
        }
        clearScreenshotQueue()
        applyFpsCaps()
        scheduler.requestSoon()
    }

    /** The capture rate is the lowest of: the byte-budget cap, the server's `max_fps` (screenshots), 4 fps (wireframes). */
    private fun applyFpsCaps() {
        val caps = ArrayList<Double>(3)
        if (mode == ReplayMode.SCREENSHOT) {
            governor?.fpsCap?.let(caps::add)
            remoteMaxFps?.let(caps::add)
        } else if (baseFps > WIREFRAME_MAX_FPS) {
            caps.add(WIREFRAME_MAX_FPS)
        }
        scheduler.capFps(caps.minOrNull())
    }

    /** Stops all main-thread activity and flushes the open chunk. Nothing stays posted or attached. */
    private fun halt(flushPipeline: Boolean) {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(flushRunnable)
        scheduler.stop() // removes its callbacks and every draw listener
        attachedRoots.clear()
        unwrapAllTouch()
        pendingFrame.set(null)
        pendingShot.getAndSet(null)?.raw?.release()
        submit {
            drainChunks()
            closeBatch()
            closeScreenChunk()
            if (flushPipeline) core.pipeline.flushNow()
            resetDiffState()
        }
    }

    private fun onFlushTick() {
        if (!running) return
        if (core.isDisabled()) {
            halt(flushPipeline = false)
            return
        }
        submit {
            drainChunks()
            closeBatchIfDue()
            if (core.isDisabled()) screenshot?.chunker?.discard() else screenshot?.chunker?.closeIfDue()
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

    /** FrameScheduler capture callback: walks every root window into one frame and hands it to the processor. */
    internal fun captureFrame() {
        if (!running) return
        if (core.isDisabled()) {
            halt(flushPipeline = false)
            return
        }
        val sessionId = recordingSessionId ?: return
        if (mode == ReplayMode.SCREENSHOT && screenshot != null) {
            captureScreenshot(screenshot, sessionId)
            return
        }
        val nodes = ArrayList<Wireframe>(2)
        for (root in RootViewLocator.roots(currentActivity?.get())) {
            attachRoot(root) // new dialog / popup windows
            walker.walk(root)?.let(nodes::add)
        }
        if (nodes.isEmpty()) return
        val width = nodes.maxOf { it.x + it.width }.coerceAtLeast(0)
        val height = nodes.maxOf { it.y + it.height }.coerceAtLeast(0)
        val frameRoot = Wireframe(FRAME_ROOT_ID, 0, 0, width, height, WireframeType.CONTAINER, children = nodes)
        val screen = core.screens.currentScreen
        // Sanitized (redacted, path ids masked, URL-safe) for output; the raw name is kept only to compare screens.
        val frame = Frame(
            frameRoot, hrefFor(screen), screen ?: "", width, height, core.clock.nowMs(), sessionId, forceFullNext,
        )
        forceFullNext = false
        submitFrame(frame)
    }

    private fun captureScreenshot(kit: ScreenshotKit, sessionId: String) {
        val activity = currentActivity?.get()
        val roots = RootViewLocator.roots(activity)
        if (roots.isEmpty()) return
        for (root in roots) attachRoot(root) // new dialog / popup windows
        val screen = core.screens.currentScreen
        val href = hrefFor(screen)
        val force = forceFullNext
        forceFullNext = false
        val density = roots[0].resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        kit.capturer.capture(roots, activity?.window, core.clock.nowMs()) { raw ->
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
        if (mode == ReplayMode.SCREENSHOT && screenshot != null) {
            submit { screenshot.chunker.addTouch(sessionId, ScreenTouch(ts, x, y, kind)) }
        } else {
            submit {
                // Only on top of a snapshot of the same session; a touch before the first frame has nothing to show.
                if (base != null && sessionId == chunkSessionId) emit(chunker.add(encoder.touch(kind, x, y, ts), false), sessionId)
            }
        }
        if (kind == TouchKind.UP && scheduler.currentFps >= baseFps) scheduler.requestSoon()
    }

    // endregion

    // region processing context

    private fun submit(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                forceFullAfterFailure = true
                SdkLog.w("replay processing failed", t)
            }
        }
    }

    private fun submitFrame(frame: Frame) {
        if (pendingFrame.getAndSet(frame) != null) frameDropped.set(true)
        if (frameJobQueued.compareAndSet(false, true)) {
            submit {
                frameJobQueued.set(false)
                pendingFrame.getAndSet(null)?.let(::process)
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
            val kit = screenshot ?: return
            if (job.sessionId != shotSessionId) {
                kit.chunker.close()
                shotSessionId = job.sessionId
                kit.encoder.resetDuplicates()
            }
            if (core.isDisabled()) {
                kit.chunker.discard()
                return
            }
            val gov = governor
            val quality = gov?.quality ?: replayConfig.quality
            var frame = kit.encoder.encode(job.raw, quality.preset(), job.href, job.density, job.force) ?: return
            var added = kit.chunker.addFrame(job.sessionId, frame)
            if (added == ScreenChunker.Add.TOO_LARGE) {
                // A frame that cannot fit a chunk: once more one preset lower, else dropped.
                val lower = quality.lower()
                if (lower != null) {
                    frame = kit.encoder.encode(job.raw, lower.preset(), job.href, job.density, true) ?: return
                    added = kit.chunker.addFrame(job.sessionId, frame)
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
        val kit = screenshot ?: return
        if (core.isDisabled()) kit.chunker.discard() else kit.chunker.close()
        kit.encoder.resetDuplicates()
    }

    private fun process(frame: Frame) {
        if (frame.sessionId != chunkSessionId) {
            // Everything recorded so far belongs to the previous session.
            drainChunks()
            closeBatch()
            chunkSessionId = frame.sessionId
            base = null
        }
        val previous = base
        // Both flags are consumed unconditionally (no short-circuit), so a stale one cannot force a later frame.
        val dropped = frameDropped.getAndSet(false)
        val chunkerDropped = chunker.consumeDropped()
        val full = previous == null ||
            frame.forceFull ||
            dropped ||
            chunkerDropped ||
            forceFullAfterFailure ||
            frame.screenKey != baseScreenKey ||
            frame.href != baseHref ||
            frame.width != baseWidth ||
            frame.height != baseHeight ||
            frame.ts - lastFullAtMs >= CHECKOUT_MS
        // Cleared first: a failure below sets it again (submit's catch) and the next frame is full.
        base = null
        forceFullAfterFailure = false
        val out = ArrayList<Map<String, Any?>>(2)
        if (full) {
            out += chunker.add(encoder.meta(frame.href, frame.width, frame.height, frame.ts), false)
            out += chunker.add(encoder.fullSnapshot(frame.root, frame.ts), true)
            lastFullAtMs = frame.ts
        } else {
            encoder.mutation(WireframeDiffer.diff(previous!!, frame.root), frame.ts)?.let { out += chunker.add(it, false) }
        }
        base = frame.root
        baseHref = frame.href
        baseScreenKey = frame.screenKey
        baseWidth = frame.width
        baseHeight = frame.height
        emit(out, frame.sessionId)
    }

    private fun drainChunks() {
        val sessionId = chunkSessionId ?: return
        emit(chunker.drain(), sessionId)
    }

    /** After a stop: the next frame starts from scratch with a full snapshot. The chunk session is kept. */
    private fun resetDiffState() {
        base = null
        baseHref = null
        baseScreenKey = null
        encoder.reset()
        chunker.consumeDropped()
        frameDropped.set(false)
        forceFullAfterFailure = false
    }

    private fun emit(chunks: List<Map<String, Any?>>, sessionId: String) {
        if (chunks.isEmpty()) return
        if (core.isDisabled()) {
            batcher.discard()
            return
        }
        for (chunk in chunks) batcher.add(sessionId, chunk)
    }

    /** Closes the open batch now; with the SDK disabled (opt-out / kill-switch) it is dropped instead. */
    private fun closeBatch() {
        if (core.isDisabled()) batcher.discard() else batcher.close()
    }

    private fun closeBatchIfDue() {
        if (core.isDisabled()) batcher.discard() else batcher.closeIfDue()
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
        submit {
            batcher.markError()
            screenshot?.chunker?.markError()
        }
    }

    /** Test hook: closes the open chunk and the open batch now (the timer closes the batch only after its window). */
    @VisibleForTesting
    internal fun flushChunks() = onMain {
        submit {
            drainChunks()
            closeBatch()
            closeScreenChunk()
        }
    }

    /** Test hook: suspends until everything submitted so far has been processed. */
    @VisibleForTesting
    internal suspend fun awaitIdle() {
        scope.launch { }.join()
    }

    companion object {
        const val FLUSH_INTERVAL_MS: Long = 5_000
        const val CHECKOUT_MS: Long = 120_000
        private const val WIREFRAME_MAX_FPS = 4.0
        private const val SCREENSHOT_BUDGET_MS = 16L
        private const val WIREFRAME_BUDGET_MS = 8L
        private const val FRAME_ROOT_ID = Int.MIN_VALUE

        /** Wires a [FrameScheduler] whose capture callback is the new controller's [captureFrame]. */
        fun create(
            core: ARTrackerCore,
            walker: ViewWalker,
            mainHandler: Handler,
            sampler: (String, Double) -> Boolean = Sampling::isSampled,
            processing: CoroutineDispatcher? = null,
            screenshot: ScreenshotKit? = null,
            remote: ReplayRemote? = null,
            clearScreenshotQueue: () -> Unit = {},
            io: CoroutineDispatcher? = null,
        ): ReplayController {
            var controller: ReplayController? = null
            val shots = screenshot != null && core.config.replay.mode == ReplayMode.SCREENSHOT
            val scheduler = FrameScheduler(
                mainHandler, core.clock, core.config.replay.effectiveFps(),
                budgetMs = if (shots) SCREENSHOT_BUDGET_MS else WIREFRAME_BUDGET_MS,
                maxFps = if (shots) 10.0 else WIREFRAME_MAX_FPS,
            ) {
                controller?.captureFrame()
            }
            val created = ReplayController(
                core, walker, RrwebEncoder(), ReplayChunker(), scheduler, mainHandler, sampler,
                processing ?: Dispatchers.Default.limitedParallelism(1),
                screenshot, remote, clearScreenshotQueue, io ?: Dispatchers.IO,
            )
            controller = created
            return created
        }
    }
}
