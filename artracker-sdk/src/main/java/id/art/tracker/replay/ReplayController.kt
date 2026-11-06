package id.art.tracker.replay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import androidx.annotation.VisibleForTesting
import id.art.tracker.REPLAY_CHUNK_NAME
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ItemFactory
import id.art.tracker.core.SdkLog
import id.art.tracker.effectiveFps
import id.art.tracker.lifecycle.ActivityTracker
import id.art.tracker.replay.capture.FrameScheduler
import id.art.tracker.replay.capture.RootViewLocator
import id.art.tracker.replay.capture.ViewWalker
import id.art.tracker.replay.diff.WireframeDiffer
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import id.art.tracker.replay.rrweb.RrwebEncoder
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
 * Output: Meta + FullSnapshot at the first frame, on screen (href) or size change, every [CHECKOUT_MS], after a
 * dropped frame, after a session change or a restart; otherwise a Mutation when the diff is not empty. Chunks are
 * enqueued as `session`/`replay_chunk` items stamped with the session they were recorded in, every
 * [FLUSH_INTERVAL_MS], at 20 KB, and when recording stops.
 *
 * Privacy: the only text in the output comes from the walker (already masked) and the encoder's fixed labels; the
 * controller adds only the `href`, built by [ReplayHref] (redacted, path ids masked, URL-safe), and never reads view
 * text itself.
 *
 * Memory: the last processed [Wireframe] (diff base), one pending frame and the open chunk.
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
) : ActivityTracker.WindowListener {

    private class Frame(
        val root: Wireframe,
        val href: String,
        val width: Int,
        val height: Int,
        val ts: Long,
        val sessionId: String,
        val forceFull: Boolean,
    )

    private val scope = CoroutineScope(
        SupervisorJob() + processing + CoroutineExceptionHandler { _, t -> SdkLog.w("replay processing failed", t) },
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
    private var currentActivity: WeakReference<Activity>? = null
    private val attachedRoots = ArrayList<WeakReference<View>>()
    private val wrappedWindows = ArrayList<WeakReference<Window>>()
    private val flushRunnable = Runnable { onFlushTick() }
    // endregion

    // region hand-off between the threads
    private val pendingFrame = AtomicReference<Frame?>(null)
    private val frameJobQueued = AtomicBoolean(false)
    private val frameDropped = AtomicBoolean(false)

    /** Clock time of the last full snapshot; written by the processing context, read on main. */
    @Volatile
    private var lastFullAtMs = 0L
    // endregion

    // region processing-context state
    private var base: Wireframe? = null
    private var baseHref: String? = null
    private var baseWidth = 0
    private var baseHeight = 0
    private var chunkSessionId: String? = null
    private var forceFullAfterFailure = false
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
        scheduler.start() // T21 contract: start BEFORE attach
        reattachRoots()
        currentActivity?.get()?.let(::wrapTouch)
        mainHandler.removeCallbacks(flushRunnable)
        mainHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
        scheduler.requestSoon()
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
        submit {
            drainChunks()
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
        submit { drainChunks() }
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
        val nodes = ArrayList<Wireframe>(2)
        for (root in RootViewLocator.roots(currentActivity?.get())) {
            attachRoot(root) // new dialog / popup windows
            walker.walk(root)?.let(nodes::add)
        }
        if (nodes.isEmpty()) return
        val width = nodes.maxOf { it.x + it.width }.coerceAtLeast(0)
        val height = nodes.maxOf { it.y + it.height }.coerceAtLeast(0)
        val frameRoot = Wireframe(FRAME_ROOT_ID, 0, 0, width, height, WireframeType.CONTAINER, children = nodes)
        // Sanitized (redacted, path ids masked, URL-safe) once here; the processor compares this same value.
        val href = ReplayHref.of(core.screens.currentScreen)
        val frame = Frame(frameRoot, href, width, height, core.clock.nowMs(), sessionId, forceFullNext)
        forceFullNext = false
        submitFrame(frame)
    }

    private fun onTouch(kind: TouchKind, rawX: Float, rawY: Float, density: Float) {
        if (!running) return
        val sessionId = recordingSessionId ?: return
        val x = (rawX / density).roundToInt()
        val y = (rawY / density).roundToInt()
        val ts = core.clock.nowMs()
        submit {
            // Only on top of a snapshot of the same session; a touch before the first frame has nothing to show.
            if (base != null && sessionId == chunkSessionId) emit(chunker.add(encoder.touch(kind, x, y, ts), false), sessionId)
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

    private fun process(frame: Frame) {
        if (frame.sessionId != chunkSessionId) {
            // Everything recorded so far belongs to the previous session.
            drainChunks()
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
        encoder.reset()
        chunker.consumeDropped()
        frameDropped.set(false)
        forceFullAfterFailure = false
    }

    private fun emit(chunks: List<Map<String, Any?>>, sessionId: String) {
        if (chunks.isEmpty() || core.isDisabled()) return
        val items = ItemFactory(core.clock, { sessionId }, { core.identity.userId })
        for (chunk in chunks) core.pipeline.enqueue(items.session(REPLAY_CHUNK_NAME, chunk))
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

    /** Test hook: closes the open chunk now (as the 5 s timer would). */
    @VisibleForTesting
    internal fun flushChunks() = onMain { submit { drainChunks() } }

    /** Test hook: suspends until everything submitted so far has been processed. */
    @VisibleForTesting
    internal suspend fun awaitIdle() {
        scope.launch { }.join()
    }

    companion object {
        const val FLUSH_INTERVAL_MS: Long = 5_000
        const val CHECKOUT_MS: Long = 120_000
        private const val FRAME_ROOT_ID = Int.MIN_VALUE

        /** Wires a [FrameScheduler] whose capture callback is the new controller's [captureFrame]. */
        fun create(
            core: ARTrackerCore,
            walker: ViewWalker,
            mainHandler: Handler,
            sampler: (String, Double) -> Boolean = Sampling::isSampled,
            processing: CoroutineDispatcher? = null,
        ): ReplayController {
            var controller: ReplayController? = null
            val scheduler = FrameScheduler(mainHandler, core.clock, core.config.replay.effectiveFps()) {
                controller?.captureFrame()
            }
            val created = if (processing == null) {
                ReplayController(core, walker, RrwebEncoder(), ReplayChunker(), scheduler, mainHandler, sampler)
            } else {
                ReplayController(core, walker, RrwebEncoder(), ReplayChunker(), scheduler, mainHandler, sampler, processing)
            }
            controller = created
            return created
        }
    }
}
