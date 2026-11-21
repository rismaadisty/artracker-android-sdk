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
import id.art.tracker.replay.capture.CaptureActivityGovernor
import id.art.tracker.replay.capture.CaptureState
import id.art.tracker.replay.capture.FrameScheduler
import id.art.tracker.replay.capture.ImeVisibilityWatcher
import id.art.tracker.replay.capture.RootViewLocator
import id.art.tracker.replay.capture.TriggerKind
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
 * and either `start(manual = true)` was called or `config.replay.enabled` and [RecordingMode.select] picks a mode for
 * the CURRENT session (re-evaluated at every new session).
 *
 * Modes: [RecordingMode.FULL] uploads every chunk. [RecordingMode.BUFFER] records the same way, in
 * [ScreenChunker.BUFFER_WINDOW_MS] chunks stored held (not uploaded); an error ([onError]) that [ErrorTriggerPolicy]
 * admits promotes the session's held chunks and keeps chunks ready for [FOLLOW_UP_MS]. A BUFFER session that ends
 * without a trigger has its held chunks discarded.
 *
 * Threading:
 * - Main thread: every public method hops there ([onMain]); it owns the lifecycle state, the [scheduler], the touch
 *   wrappers and the capture itself ([captureFrame]: [RootViewLocator] + [ScreenshotKit.capturer] composite the
 *   windows and collect the mask regions, read-only view access).
 * - One serial background context ([processing], `Dispatchers.Default.limitedParallelism(1)` in production) owns the
 *   [ScreenshotKit.encoder] and [ScreenshotKit.chunker]: it masks, scales, compresses, deduplicates and chunks the
 *   frames ([ScreenChunker]), under a byte budget ([ByteBudgetGovernor]). Frames use latest-frame-wins: at most one
 *   waits; a newer frame replaces it. It also admits and promotes error triggers ([onError]), so they never wait for the
 *   main thread (blocked during an ANR); other threads see what is recorded through the `@Volatile` [recording].
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
    private val sink: ReplaySink = core.replaySink,
    processing: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    imeVisibility: ((View) -> Boolean?)? = null,
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

    /** Mode of [recordingSessionId] while [running]; the chunker gets it through [applyMode]. */
    private var recordingMode: RecordingMode? = null

    /** BUFFER only: until this clock time, closed chunks are ready (the follow-up after an admitted error). */
    private var followUpUntilMs: Long? = null
    private var forceFullNext = false
    private var currentActivity: WeakReference<Activity>? = null
    private val attachedRoots = ArrayList<WeakReference<View>>()
    private val wrappedWindows = ArrayList<WeakReference<Window>>()
    private val flushRunnable = Runnable { onFlushTick() }
    private val stateRunnable = Runnable { onStateTick() }
    private val dormantFlushRunnable = Runnable { onDormantFlush() }

    /** Active / Idle / Dormant lifecycle driven by real user input (named apart from the byte-budget [governor]). */
    private val captureGovernor =
        CaptureActivityGovernor(baseFps, replayConfig.idleAfterMs, replayConfig.dormantAfterMs)
    private val imeWatcher = if (imeVisibility != null) {
        ImeVisibilityWatcher(imeVisibility) { handleTrigger(TriggerKind.KEYBOARD) }
    } else {
        ImeVisibilityWatcher { handleTrigger(TriggerKind.KEYBOARD) }
    }
    private var appliedState = CaptureState.ACTIVE
    private var dormantFlushPending = false

    /** A trigger asked for the capture that has not run yet; that capture already shows what the trigger is about. */
    private var triggerCapturePending = false

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
        val mode: RecordingMode,
        val force: Boolean,
    )

    /** What is being recorded: [recordingSessionId] and [recordingMode] while [running]. */
    private class Recording(val sessionId: String, val mode: RecordingMode)

    /** Snapshot for other threads ([onError]); written on the main thread wherever it changes ([publishRecording]). */
    @Volatile
    private var recording: Recording? = null

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

    /**
     * Session and mode the chunker records for, set by the submit that applies the mode ([applyMode]). A FULL -> FULL
     * session change applies no mode and leaves them as they were.
     */
    private var modeSessionId: String? = null
    private var appliedMode: RecordingMode? = null

    /** Rate limit of the error triggers ([triggerOnError]). */
    private val errorPolicy = ErrorTriggerPolicy()
    // endregion

    init {
        // A rage burst that completes while no chunk is open (a static screen sends no frames) asks for a frame now,
        // forced so it is sent although it equals the previous one: it opens the chunk that carries the flag.
        screenshot.chunker.onRagePending = {
            post {
                if (running) {
                    forceFullNext = true
                    scheduler.requestSoon()
                }
            }
        }
    }

    /** Main-thread view of whether recording is active (tests and diagnostics). */
    @VisibleForTesting
    internal val isRunning: Boolean get() = running

    /** Main-thread view of the recording mode; null when not recording. */
    @VisibleForTesting
    internal val mode: RecordingMode? get() = if (running) recordingMode else null

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
            handleTrigger(TriggerKind.SCREEN)
        }
    }

    /**
     * An important UI change (screen, window/dialog, error, API response, keyboard, app request): captures a frame
     * after the trigger's debounce in every capture state, subject to the NETWORK rate limit and to the session not
     * being expired. Any thread; the work happens on the main thread.
     */
    internal fun onTrigger(kind: TriggerKind) {
        post { handleTrigger(kind) }
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

    /** How [sessionId] is recorded; null = not at all. */
    private fun modeFor(sessionId: String): RecordingMode? {
        if (userStopped || screenshotDisabled) return null
        if (!manualRequested && !replayConfig.enabled) return null
        return RecordingMode.select(sessionId, manualRequested, replayConfig, sampler)
    }

    private fun evaluate() {
        if (!foreground || core.isDisabled()) {
            halt(flushPipeline = false)
            return
        }
        val sessionId = core.session.currentId()
        val mode = modeFor(sessionId)
        // A BUFFER session that ended without a trigger: its held chunks are of no use any more. [halt] keeps the id and
        // mode of the last run, so this also covers a session that timed out while recording was stopped (background).
        val endedBuffer = recordingSessionId.takeIf { recordingMode == RecordingMode.BUFFER && it != sessionId }
        if (mode == null) {
            halt(flushPipeline = false) // when running: closes the open chunk first (held), on the same serial context
            if (endedBuffer != null) {
                submit { sink.discardHeld(endedBuffer) }
                // Forgotten once discarded, so a later re-evaluate does not discard again.
                recordingSessionId = null
                recordingMode = null
            }
            return
        }
        if (!running) {
            // Queued before begin's applyMode and before any frame of the new run.
            if (endedBuffer != null) submit { sink.discardHeld(endedBuffer) }
            begin(sessionId, mode)
        } else if (sessionId != recordingSessionId) {
            val previousMode = recordingMode
            recordingSessionId = sessionId
            recordingMode = mode
            followUpUntilMs = null
            if (previousMode == RecordingMode.BUFFER || mode != previousMode) {
                // Queued before the new session's first frame: the old chunk closes in its own mode, the next opens
                // in the new one. FULL -> FULL keeps today's path (processShot closes on the session change).
                submit {
                    closeScreenChunk(resetDuplicates = false)
                    if (endedBuffer != null) sink.discardHeld(endedBuffer)
                    applyMode(sessionId, mode)
                }
            }
            publishRecording()
            forceFullNext = true
            scheduler.requestSoon()
        } else if (mode != recordingMode) {
            // Same session, new mode (startReplay() during a BUFFER session): what was held so far is uploaded too.
            val previousMode = recordingMode
            recordingMode = mode
            followUpUntilMs = null
            submit {
                closeScreenChunk(resetDuplicates = false)
                if (previousMode == RecordingMode.BUFFER) sink.promote(sessionId)
                applyMode(sessionId, mode)
            }
            publishRecording()
        }
    }

    private fun begin(sessionId: String, mode: RecordingMode) {
        running = true
        recordingSessionId = sessionId
        recordingMode = mode
        followUpUntilMs = null
        submit { applyMode(sessionId, mode) } // before any frame of this run is submitted
        publishRecording()
        forceFullNext = true
        lastFullAtMs = core.clock.nowMs()
        governor = screenshot.governorFactory(baseFps, replayConfig.quality)
        lastGovernorCap = null
        applyFpsCaps()
        scheduler.start() // T21 contract: start BEFORE attach
        captureGovernor.onStart(core.clock.nowMs())
        appliedState = CaptureState.ACTIVE
        applyState()
        scheduleStateTick()
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
        publishRecording()
        followUpUntilMs = null
        mainHandler.removeCallbacks(flushRunnable)
        mainHandler.removeCallbacks(stateRunnable)
        mainHandler.removeCallbacks(dormantFlushRunnable)
        dormantFlushPending = false
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
        endFollowUpIfDue()
        applyState()
        if (appliedState == CaptureState.DORMANT) return // the flush timer stays stopped until the next input
        submit {
            if (core.isDisabled()) screenshot.chunker.discard() else screenshot.chunker.closeIfDue()
        }
        val now = core.clock.nowMs()
        if (captureGovernor.keyframeAllowed && now - lastFullAtMs >= CHECKOUT_MS) {
            lastFullAtMs = now
            forceFullNext = true
            scheduler.requestSoon()
        }
        mainHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    // region main thread: capture lifecycle (Active -> Idle -> Dormant)

    /** Brings the scheduler and the timers in line with the governor's state; acts only on a change. */
    private fun applyState() {
        // Idle detection follows the input we can observe. With no observed Activity (late init) or another window on
        // screen (dialog, sheet, popup: their input never reaches the Activity window) we cannot tell "away" from
        // "busy", so capture stays as active as before this lifecycle existed.
        if (!inputObservable()) captureGovernor.onUserInput(core.clock.nowMs())
        val state = captureGovernor.evaluate(core.clock.nowMs())
        scheduler.setStateFps(captureGovernor.periodicFps())
        if (state == appliedState) return
        appliedState = state
        if (state == CaptureState.DORMANT) {
            // Nothing is captured or uploaded on a timer while the user is away: close the chunk, stop the flush timer.
            mainHandler.removeCallbacks(flushRunnable)
            submit { closeScreenChunk(resetDuplicates = false) }
        }
    }

    /** True when user input reaches a window we wrap: no other window on screen and the keyboard not shown. */
    private fun inputObservable(): Boolean {
        val activity = currentActivity?.get() ?: return false
        val decor = runCatching { activity.window.decorView }.getOrNull() ?: return false
        if (imeWatcher.anyVisible()) return false // typing: the keyboard is another process, no touch reaches us
        return attachedRoots.none { ref -> ref.get()?.let { it !== decor && it.isAttachedToWindow } == true }
    }

    private fun scheduleStateTick() {
        mainHandler.removeCallbacks(stateRunnable)
        val next = captureGovernor.nextTransitionAtMs() ?: return
        // Capped: a huge threshold must not overflow the handler's uptime arithmetic (it would fire at once, in a loop).
        mainHandler.postDelayed(stateRunnable, (next - core.clock.nowMs()).coerceIn(1L, MAX_STATE_TICK_DELAY_MS))
    }

    private fun onStateTick() {
        if (!running) return
        applyState()
        scheduleStateTick()
    }

    /** Real user input (touch). The first one after Idle/Dormant captures what the user came back to, at once. */
    private fun onUserInput() {
        // While ACTIVE this is a flag flip: the state timer re-reads the deadline when it fires.
        if (!captureGovernor.onUserInput(core.clock.nowMs())) return
        forceFullNext = true
        scheduler.requestSoon(0, force = true)
        mainHandler.removeCallbacks(dormantFlushRunnable)
        dormantFlushPending = false
        mainHandler.removeCallbacks(flushRunnable)
        mainHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
        applyState()
        scheduleStateTick()
    }

    private fun handleTrigger(kind: TriggerKind) {
        if (!running) return
        // No live session to attribute a frame to once it timed out (only judged when we can see the user's input).
        if (inputObservable() && core.session.isExpired()) return
        applyState()
        val debounceMs = captureGovernor.admitTrigger(kind, core.clock.nowMs()) ?: return
        // A frame equal to the previous one is dropped by dedup (spec 4.2 rule 2); only changes whose whole point is
        // to be seen are forced through it.
        if (kind == TriggerKind.SCREEN || kind == TriggerKind.ERROR || kind == TriggerKind.MANUAL) forceFullNext = true
        scheduler.requestSoon(debounceMs, force = true)
        triggerCapturePending = true
        if (appliedState == CaptureState.DORMANT && !dormantFlushPending) {
            // The flush timer is stopped: a one-shot close makes sure this frame is queued for upload (survives a kill).
            // Not re-armed by later triggers, or a steady trickle of them would keep it from ever firing.
            dormantFlushPending = true
            mainHandler.postDelayed(dormantFlushRunnable, DORMANT_FLUSH_MS)
        }
    }

    private fun onDormantFlush() {
        dormantFlushPending = false
        if (!running || appliedState != CaptureState.DORMANT) return
        endFollowUpIfDue()
        submit { closeScreenChunk(resetDuplicates = false) }
    }

    // endregion

    private fun reattachRoots() {
        // Re-attach rather than trusting attach()'s idempotence: a root may have a new ViewTreeObserver.
        for (ref in attachedRoots) ref.get()?.let(scheduler::detach)
        attachedRoots.clear()
        for (root in RootViewLocator.roots(currentActivity?.get())) attachRoot(root)
    }

    /** True if [root] was not attached yet. */
    private fun attachRoot(root: View): Boolean {
        attachedRoots.removeAll { it.get() == null }
        if (attachedRoots.any { it.get() === root }) return false
        scheduler.attach(root)
        attachedRoots.add(WeakReference(root))
        return true
    }

    private fun detachRoot(root: View) {
        scheduler.detach(root)
        attachedRoots.removeAll { it.get() == null || it.get() === root }
    }

    private fun wrapTouch(activity: Activity) {
        val window = runCatching { activity.window }.getOrNull() ?: return
        val density = runCatching { activity.resources.displayMetrics.density }.getOrDefault(1f).takeIf { it > 0f } ?: 1f
        TouchRecorder.bindReplay(
            window,
            { kind, x, y -> onTouch(kind, x, y, density) },
            { handleTrigger(TriggerKind.WINDOW) }, // focus moved to / came back from a dialog or another window
            { onUserInput() }, // hardware keyboard / D-pad / remote
        ) ?: return
        runCatching { imeWatcher.attach(window.decorView) }
        if (wrappedWindows.none { it.get() === window }) wrappedWindows.add(WeakReference(window))
    }

    private fun unwrapTouch(window: Window) {
        TouchRecorder.unbindReplay(window)
        runCatching { imeWatcher.detach(window.decorView) }
        wrappedWindows.removeAll { it.get() == null || it.get() === window }
    }

    private fun unwrapAllTouch() {
        for (ref in wrappedWindows) ref.get()?.let(TouchRecorder::unbindReplay)
        wrappedWindows.clear()
        imeWatcher.detachAll()
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
        val mode = recordingMode ?: return
        val activity = currentActivity?.get()
        val roots = RootViewLocator.roots(activity)
        if (roots.isEmpty()) return
        var newWindow = false
        for (root in roots) if (attachRoot(root)) newWindow = true // new dialog / popup windows
        val screen = core.screens.currentScreen
        val href = hrefFor(screen)
        val force = forceFullNext
        forceFullNext = false
        val fromTrigger = triggerCapturePending
        triggerCapturePending = false
        // A window we did not know about: look again once it has rendered. Not for a capture a trigger asked for,
        // which already shows it: a dialog must not cost two frames.
        if (newWindow && !fromTrigger) handleTrigger(TriggerKind.WINDOW)
        val density = roots[0].resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        screenshot.capturer.capture(roots, activity?.window, core.clock.nowMs()) { raw ->
            if (raw == null) {
                // Busy or a mask collection that failed: nothing is sent, and a forced frame stays forced.
                if (force) post { forceFullNext = true }
                return@capture
            }
            submitShot(ShotJob(raw, href, density, sessionId, mode, force))
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
        if (kind == TouchKind.DOWN && core.session.currentId() != recordingSessionId) {
            // The core's input observer already rotated the session for this DOWN (it runs first): follow it, so the
            // wake frame below belongs to the new session, or stop if the new session is not sampled.
            evaluate()
            if (!running) return
        }
        onUserInput()
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
            // A frame captured before a session change that applied a mode (BUFFER on either side) and processed after
            // it: it would be recorded in the new session's mode under the old session (shipped, or held and never
            // promoted). FULL -> FULL keeps today's path below.
            if (job.sessionId != modeSessionId &&
                (job.mode == RecordingMode.BUFFER || appliedMode == RecordingMode.BUFFER)
            ) {
                return
            }
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

    private fun closeScreenChunk(resetDuplicates: Boolean = true) {
        if (core.isDisabled()) screenshot.chunker.discard() else screenshot.chunker.close()
        if (resetDuplicates) screenshot.encoder.resetDuplicates()
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

    /**
     * An error was logged (any thread): the open replay batch (or the next one) is flagged `has_error`. In BUFFER mode
     * an error the [errorPolicy] admits also closes the open chunk, promotes the session's held chunks for upload and
     * opens a [FOLLOW_UP_MS] window in which closed chunks are ready at once. All of it but the window's end time runs on
     * the processing context: the API 24-29 watchdog reports an ANR while the main thread is blocked, and the process
     * may be killed from the ANR dialog before it unblocks.
     */
    internal fun onError() {
        val buffer = recording?.takeIf { it.mode == RecordingMode.BUFFER } // what was recorded when the error was logged
        submit {
            screenshot.chunker.markError()
            if (buffer != null) triggerOnError(buffer.sessionId)
        }
    }

    /** Processing context: admits the error and promotes [sessionId]'s held chunks. */
    private fun triggerOnError(sessionId: String) {
        // A session or mode change queued before this one already closed the error's session.
        if (appliedMode != RecordingMode.BUFFER || modeSessionId != sessionId) return
        val now = core.clock.nowMs()
        if (!errorPolicy.admit(sessionId, now)) return
        closeScreenChunk(resetDuplicates = false) // carries has_error (marked above), still held
        sink.promote(sessionId)
        screenshot.chunker.held = false
        val until = now + FOLLOW_UP_MS
        post {
            // An admitted error during a follow-up extends it. Not after a stop or a change: that applies a mode again.
            if (running && recordingSessionId == sessionId && recordingMode == RecordingMode.BUFFER) followUpUntilMs = until
        }
    }

    /** Main thread: publishes [running], [recordingSessionId] and [recordingMode] to [recording]. */
    private fun publishRecording() {
        val sessionId = recordingSessionId
        val mode = recordingMode
        recording = if (running && sessionId != null && mode != null) Recording(sessionId, mode) else null
    }

    /** Ends the follow-up window once it is over: the chunk open now ships, later ones are held again. */
    private fun endFollowUpIfDue() {
        val until = followUpUntilMs ?: return
        if (core.clock.nowMs() < until) return
        followUpUntilMs = null
        submit {
            closeScreenChunk(resetDuplicates = false)
            screenshot.chunker.held = true
        }
    }

    /** Processing context: the chunker records [sessionId] in [mode] from its next chunk on. */
    private fun applyMode(sessionId: String, mode: RecordingMode) {
        modeSessionId = sessionId
        appliedMode = mode
        val buffer = mode == RecordingMode.BUFFER
        screenshot.chunker.held = buffer
        screenshot.chunker.windowMs = if (buffer) ScreenChunker.BUFFER_WINDOW_MS else ScreenChunker.DEFAULT_WINDOW_MS
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
        const val DORMANT_FLUSH_MS: Long = 10_000

        /** BUFFER: after an admitted error, chunks closed within this window are uploaded too. */
        const val FOLLOW_UP_MS: Long = 30_000
        private const val MAX_STATE_TICK_DELAY_MS: Long = 3_600_000
        private const val BUDGET_MS = 16L
        private const val MAX_FPS = 10.0

        /** Wires a [FrameScheduler] whose capture callback is the new controller's [captureFrame]. */
        fun create(
            core: ARTrackerCore,
            screenshot: ScreenshotKit,
            mainHandler: Handler,
            sampler: (String, Double) -> Boolean = Sampling::isSampled,
            sink: ReplaySink = core.replaySink,
            processing: CoroutineDispatcher? = null,
            imeVisibility: ((View) -> Boolean?)? = null,
        ): ReplayController {
            var controller: ReplayController? = null
            val scheduler = FrameScheduler(
                mainHandler, core.clock, core.config.replay.effectiveFps(), budgetMs = BUDGET_MS, maxFps = MAX_FPS,
            ) {
                controller?.captureFrame()
            }
            val created = ReplayController(
                core, screenshot, scheduler, mainHandler, sampler, sink,
                processing ?: Dispatchers.Default.limitedParallelism(1), imeVisibility,
            )
            controller = created
            return created
        }
    }
}
