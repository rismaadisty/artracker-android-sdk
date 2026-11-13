package id.art.tracker.core

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.annotation.VisibleForTesting
import id.art.tracker.ARTrackerConfig
import id.art.tracker.Diagnostics
import id.art.tracker.LogLevel
import id.art.tracker.TelemetryItem
import id.art.tracker.lifecycle.ActivityTracker
import id.art.tracker.lifecycle.AppLifecycleObserver
import id.art.tracker.lifecycle.NavTracker
import id.art.tracker.lifecycle.ScreenTracker
import id.art.tracker.perf.AndroidDeviceProbe
import id.art.tracker.perf.ConnectivityNetworkProbe
import id.art.tracker.perf.DeviceProbe
import id.art.tracker.perf.FrameMetricsFrameSource
import id.art.tracker.perf.MemoryWarningCallbacks
import id.art.tracker.perf.NetworkProbe
import id.art.tracker.perf.PerfMonitor
import id.art.tracker.perf.PerfSampler
import id.art.tracker.perf.ProcSelfReader
import id.art.tracker.perf.WindowListenerRegistry
import id.art.tracker.privacy.AttributeRedactor
import id.art.tracker.replay.ReplayController
import id.art.tracker.replay.ReplayControllerFactory
import id.art.tracker.replay.ReplayIntake
import id.art.tracker.replay.ReplaySink
import id.art.tracker.telemetry.Breadcrumbs
import id.art.tracker.telemetry.CrashHandler
import id.art.tracker.telemetry.CrashItemBuilder
import id.art.tracker.telemetry.CrashRecovery
import id.art.tracker.telemetry.CrashStore
import id.art.tracker.telemetry.ErrorDeduper
import id.art.tracker.telemetry.ExitInfoCollector
import id.art.tracker.telemetry.Stacktrace
import id.art.tracker.transport.Backoff
import id.art.tracker.transport.CustomSenderAdapter
import id.art.tracker.transport.EventPipeline
import id.art.tracker.transport.EventStore
import id.art.tracker.transport.Flusher
import id.art.tracker.transport.FlusherRegistry
import id.art.tracker.transport.IngestClient
import id.art.tracker.transport.PayloadEncoder
import id.art.tracker.transport.ReplayClient
import id.art.tracker.transport.ReplayFlusher
import id.art.tracker.transport.ReplayKicker
import id.art.tracker.transport.ReplayStore
import id.art.tracker.transport.TransportConfigStore
import id.art.tracker.transport.WorkManagerScheduler
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wires the SDK components together (ARCHITECTURE.md section 4). One instance per process, owned by the
 * `ARTracker` facade; the facade guards every call with `runCatching`, so the methods here may assume a sane
 * caller but must still never block the calling thread on I/O.
 *
 * Threading: [scope] is `SupervisorJob + Dispatchers.IO.limitedParallelism(1)`, so queue mutations, the
 * persistence of the opt-out flag and the heavy part of init run one after another on a single background
 * thread, in launch order. Network flushes run on `Dispatchers.IO` (see `flushContext`) so that a request in
 * flight never stalls that thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ARTrackerCore(
    val app: Application,
    val config: ARTrackerConfig,
    val clock: Clock = Clock.SYSTEM,
    /** Builds the replay controller (tests inject one); must not link Compose types. */
    private val replayFactory: (ARTrackerCore, Handler) -> ReplayController = ReplayControllerFactory::create,
    /** Where closed replay batches go; tests inject one. The default queues them for upload (see [replaySink]). */
    replaySink: ReplaySink? = null,
    /** Builds the performance monitor when `performance.enabled` (tests inject one with fakes); null = the real one. */
    private val perfFactory: ((ARTrackerCore) -> PerfMonitor)? = null,
    /** The wait between two samples; tests inject one they control instead of real time. */
    private val perfDelay: suspend (Long) -> Unit = { delay(it) },
) {
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO.limitedParallelism(1) +
            // Whatever goes wrong inside the SDK must never reach the host app's uncaught-exception handler.
            CoroutineExceptionHandler { _, t -> SdkLog.w("unhandled exception in the SDK scope", t) },
    )

    /** Transport prefs also hold the kill-switch so a fresh-process `FlushWorker` honours opt-out and 401 trips. */
    private val transportPrefs = SharedPrefsStore(app, TransportConfigStore.PREFS_NAME)
    private val killSwitch = KillSwitch(transportPrefs, clock)

    val identity = IdentityStore(SharedPrefsStore(app, IDENTITY_PREFS))

    /** Last actions of the user/app, attached to crashes and handled errors (never sent on their own). */
    val breadcrumbs = Breadcrumbs()
    private val errorDeduper = ErrorDeduper()
    private val crashStore = CrashStore(File(app.filesDir, CRASH_DIR))

    /**
     * Lock-free snapshot of what the crash handler needs, kept up to date by the paths below: the handler runs on
     * a dying thread that may hold any lock, so it must not call [session] (synchronized) or [device] (lazy).
     * The user id is read straight from [identity] (a volatile) and the screen from [screens] (a volatile).
     */
    @Volatile
    private var crashSessionId: String? = null

    @Volatile
    private var crashAppVersion: String? = config.appVersion?.takeIf { it.isNotBlank() }

    private var crashHandler: CrashHandler? = null

    /** Lazy: building it may query the PackageManager, which init must not do on the main thread. */
    val device: DeviceContext by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DeviceContext(app, config.appVersion) }

    /** User id to stamp on the `session_end` of a session that `reset()` is just closing (user already cleared). */
    @Volatile
    private var endedSessionUserId: String? = null

    private val sessionListener = object : SessionManager.Listener {
        override fun onSessionStart(id: String) {
            crashSessionId = id
            runCatching {
                if (isDisabled()) return@runCatching
                // Device facts are gathered lazily on the pipeline thread: nothing here may cost the caller.
                val attrs = LazyAttributes { device.sessionStartAttributes() }
                val sessionItems = itemsFor(id, identity.userId)
                emit(sessionItems.session("session_start", attrs))
                // The screen still on display belongs to this new session too (no-op for the very first one).
                screens.onSessionStarted(sessionItems)
            }
            // Replay sampling is per session: re-evaluated (and a full snapshot forced) for the new id.
            runCatching { replay?.onSessionStarted() }
        }

        override fun onSessionEnd(id: String, durationMs: Long) {
            // Samples buffered in this session go out under its id, before the session closes.
            runCatching { perf?.flush() }
            runCatching {
                if (isDisabled()) return@runCatching
                // Stamped with the id of the session that ended, never with the current one.
                val user = endedSessionUserId ?: identity.userId
                emit(itemsFor(id, user).session("session_end", mapOf("duration_ms" to durationMs)))
            }
        }
    }

    val session: SessionManager = SessionManager(
        store = SharedPrefsStore(app, SESSION_PREFS),
        clock = clock,
        timeoutMs = config.sessionTimeoutMs,
        listener = sessionListener,
    )

    val items: ItemFactory = ItemFactory(
        clock,
        { session.currentId().also { crashSessionId = it } },
        { identity.userId },
    )

    /**
     * Funnel for every `screen_view`. Items are dropped here while the SDK is disabled (opt-out / 401
     * kill-switch). `onScreenChanged` runs inside the tracker's monitor, so it only does a lock-free breadcrumb add.
     */
    val screens: ScreenTracker = ScreenTracker(
        { item -> if (!isDisabled()) emit(item) },
        items,
        onScreenChanged = { previous, current ->
            breadcrumbs.add("screen", current, if (previous != null) mapOf("previous" to previous) else emptyMap())
            replay?.onScreenChanged() // only posts to the main thread
        },
    )

    /** Session replay; created in [start] when enabled, or lazily by [startReplay]. */
    @Volatile
    private var replay: ReplayController? = null
    private val replayLock = Any()

    /** The resumed Activity (main thread), handed to a replay controller that is created later. */
    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    /** Further window listeners (the performance frame source); the replay controller keeps its own hook above. */
    private val extraWindowListeners = CopyOnWriteArrayList<ActivityTracker.WindowListener>()

    private val windowListener = object : ActivityTracker.WindowListener {
        override fun onActivityResumed(a: Activity) {
            resumedActivity = WeakReference(a)
            replay?.onActivityResumed(a)
            extraWindowListeners.forEach { runCatching { it.onActivityResumed(a) } }
        }

        override fun onActivityPaused(a: Activity) {
            if (resumedActivity?.get() === a) resumedActivity = null
            replay?.onActivityPaused(a)
            extraWindowListeners.forEach { runCatching { it.onActivityPaused(a) } }
        }
    }

    /** Lets the frame source follow the resumed Activity; an Activity that is already resumed is replayed on [add]. */
    internal val windowRegistry: WindowListenerRegistry = object : WindowListenerRegistry {
        override fun add(l: ActivityTracker.WindowListener) {
            extraWindowListeners += l
            resumedActivity?.get()?.let { runCatching { l.onActivityResumed(it) } }
        }

        override fun remove(l: ActivityTracker.WindowListener) {
            extraWindowListeners -= l
        }
    }

    /** Session profiling; created in [start] only when `performance.enabled`. */
    @Volatile
    internal var perf: PerfMonitor? = null
        private set

    private val perfLoopLock = Any()

    @Volatile
    private var perfJob: Job? = null
    private var memoryCallbacks: MemoryWarningCallbacks? = null

    private val activityTracker = ActivityTracker(screens, windowListener)
    private val navTracker = NavTracker(screens)

    /** Hooks of [ProcessLifecycleOwner]; added on the main thread (see [registerProcessObserver]). */
    private val processObserver = AppLifecycleObserver(onForeground = ::onForeground, onBackground = ::onBackground)
    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Handler(Looper.getMainLooper()) }

    /**
     * Runs on the main thread once the kill-switch deadline has passed: recording that [isDisabled] stopped is started
     * again while the app is still in the foreground (replay, the flush timer, profiling). If another instance over the
     * same prefs (a fresh-process `FlushWorker`) moved the deadline further out meanwhile, it waits for the new one.
     */
    private val resumeAfterTrip = Runnable {
        runCatching {
            val until = killSwitch.disabledUntilMs()
            if (until > 0L) {
                scheduleResume(until)
            } else if (foreground && !isDisabled()) {
                replay?.onDisabledChanged()
                pipeline.startTimer()
                startPerfLoop()
            }
        }.onFailure { SdkLog.w("resuming after the kill-switch failed", it) }
    }

    /** Replaces any pending resume by one at [untilMs] (plus a little slack). Posts to the main thread. */
    private fun scheduleResume(untilMs: Long) {
        onMain {
            mainHandler.removeCallbacks(resumeAfterTrip)
            val wait = (untilMs - clock.nowMs()).coerceAtLeast(0L) + RESUME_SLACK_MS
            mainHandler.postDelayed(resumeAfterTrip, wait)
        }
    }

    /** Cheap to construct: the database is only opened on first use, in the background. */
    private val queue = EventStore(app, clock)

    private val scheduler = WorkManagerScheduler(app)

    val flusher = Flusher(
        queue = queue,
        encoder = PayloadEncoder { device.envelopeContext(config.environment) },
        sender = config.network.sender?.let { CustomSenderAdapter(it, config.endpoint, config.apiKey) }
            ?: IngestClient(config.endpoint, config.apiKey),
        killSwitch = killSwitch,
        clock = clock,
        backoff = Backoff(),
        scheduler = scheduler,
    )

    /**
     * Replay uploads. All three are lazy so the replay database is never opened (or created) unless replay is used.
     * The first use, off the main thread (the replay processing context or [scope]), recovers batches orphaned by a
     * dead process and purges the expired ones.
     */
    private val replayStoreLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReplayStore(app, clock).also {
            it.resetOrphaned()
            it.purgeOlderThan(clock.nowMs() - RETENTION_MS)
        }
    }
    private val replayQueue: ReplayStore by replayStoreLazy
    // The host's ARTrackerSender (when set) carries the replay upload too, so pinning, proxy and Chucker apply.
    private val replayClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReplayClient(config.endpoint, config.apiKey, sender = config.network.sender)
    }
    private val replayFlusherLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReplayFlusher(
            replayQueue, replayClient, killSwitch, clock, Backoff(), scheduler,
            onScreenshotDisabled = { runCatching { replay?.onScreenshotDisabled() } },
        ).also { FlusherRegistry.replay = it }
    }
    private val replayFlusher: ReplayFlusher by replayFlusherLazy
    private val replayKicker by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // The upload blocks on HTTP, so it runs on Dispatchers.IO rather than on the single-threaded [scope] thread.
        ReplayKicker(scope, Dispatchers.IO, replayFlusher)
    }

    /** Receives every closed replay chunk from the replay controller: queued, then uploaded in the background. */
    internal val replaySink: ReplaySink =
        replaySink ?: ReplayIntake(::isDisabled, { replayQueue }, { replayKicker.kick() })

    /** Last launched replay housekeeping (opt-out clearing, kicking); awaited by [awaitIdle]. */
    @Volatile
    private var replayJob: Job? = null

    val pipeline: EventPipeline

    @Volatile
    private var foreground = false

    @Volatile
    private var persistJob: Job? = null

    /** The second half of init (crash recovery and the initial flush decision); awaited by [awaitIdle]. */
    @Volatile
    private var backgroundInitJob: Job? = null

    /** Whether the queue already held items from an earlier run; decided before this run enqueues anything. */
    @Volatile
    private var leftoverItems = false

    init {
        // Must be launched BEFORE the pipeline: the scope runs one task at a time in launch order, so the
        // queue maintenance below completes before the consumer or any flush can touch the database.
        // A 401 stops replay at once (it would otherwise notice at its next flush tick) and schedules the way back.
        killSwitch.onTrip = { until ->
            runCatching { replay?.onDisabledChanged() }
            scheduleResume(until)
        }
        scope.launch { leftoverItems = maintainQueueAndSaveTransportConfig() }
        pipeline = EventPipeline(
            scope = scope,
            queue = queue,
            flusher = flusher,
            clock = clock,
            beforeSend = config.beforeSend,
            flushIntervalMs = config.flushIntervalMs,
            flushContext = Dispatchers.IO,
            // An error in the session flags the replay batch it happened in (the viewer jumps to it).
            onItemAccepted = { replay?.noteError() },
        )
    }

    /**
     * Second half of init, after construction: publishes the flusher, schedules the remaining background work
     * and starts the lifecycle observers. The foreground is entered by [processObserver]: `LifecycleRegistry`
     * replays `ON_START` to a late observer, so when init runs after the first Activity started, [onForeground]
     * still fires exactly once (no manual call, which would double count).
     */
    fun start() {
        // First thing, before any further work: a crash during the rest of init is already captured.
        crashHandler = CrashHandler.install(crashStore, ::buildCrashItem, shouldCapture = { !killSwitch.optedOut })
        crashSessionId = session.peekId()
        FlusherRegistry.current = flusher
        backgroundInitJob = scope.launch {
            runCatching { onBackgroundInit() }.onFailure { SdkLog.w("background init hook failed", it) }
            // Items left by a previous run (including a crash) are sent right away; a fresh queue is not flushed.
            if (leftoverItems) pipeline.flushNow()
        }
        app.registerActivityLifecycleCallbacks(activityTracker)
        // Before the process observer: the foreground it replays then finds the controller in place.
        if (config.replay.enabled) ensureReplay()?.start(manual = false)
        if (config.performance.enabled) startPerformance()
        registerProcessObserver()
        killSwitch.disabledUntilMs().takeIf { it > 0L }?.let(::scheduleResume)
    }

    /** Creates the monitor and registers the memory callbacks; nothing else (no thread, no loop) until foreground. */
    private fun startPerformance() {
        runCatching {
            perf = (perfFactory ?: ::createPerfMonitor)(this)
            val callbacks = MemoryWarningCallbacks({ foreground }) { level ->
                if (!isDisabled()) emit(items.event("perf.memory_warning", mapOf("level" to level)))
            }
            app.applicationContext.registerComponentCallbacks(callbacks)
            memoryCallbacks = callbacks
        }.onFailure { SdkLog.w("performance monitoring unavailable", it) }
    }

    private fun createPerfMonitor(owner: ARTrackerCore): PerfMonitor {
        val probe = AndroidDeviceProbe(app)
        val network = ConnectivityNetworkProbe(app)
        val frames = if (config.performance.trackFrames) FrameMetricsFrameSource(owner.windowRegistry) else null
        return owner.newPerfMonitor(PerfSampler(ProcSelfReader, frames, probe, network, clock, SystemClock::elapsedRealtime), probe, network)
    }

    /** A monitor wired to this core: the current session, the user and the pipeline (items stay off while disabled). */
    internal fun newPerfMonitor(sampler: PerfSampler, device: DeviceProbe, network: NetworkProbe): PerfMonitor = PerfMonitor(
        sampler = sampler,
        device = device,
        network = network,
        intervalMs = config.performance.sampleIntervalMs,
        sessionId = { session.peekId() },
        emit = { sessionId, build ->
            if (!isDisabled()) emit(build(itemsFor(sessionId, endedSessionUserId ?: identity.userId)))
        },
    )

    /** Starts the frame source and the sampling loop on [scope]; idempotent while the loop runs. */
    private fun startPerfLoop() {
        val monitor = perf ?: return
        // Callers race (main thread on foreground, any thread on opt-in): check and launch are one step, and the
        // foreground state is re-read inside it so a loop is never started after [onBackground] stopped it.
        synchronized(perfLoopLock) {
            if (perfJob?.isActive == true || !foreground || isDisabled()) return
            runCatching { monitor.onForeground() }.onFailure { SdkLog.w("starting performance sampling failed", it) }
            val interval = config.performance.sampleIntervalMs
            perfJob = scope.launch {
                while (isActive) {
                    perfDelay(interval)
                    if (isDisabled()) continue
                    runCatching { monitor.tick() }.onFailure { SdkLog.w("performance sample failed", it) }
                }
            }
        }
    }

    private fun stopPerfLoop() {
        synchronized(perfLoopLock) {
            perfJob?.cancel()
            perfJob = null
        }
    }

    /** The replay controller, created once (cheap: no view or Compose access happens here). */
    private fun ensureReplay(): ReplayController? {
        replay?.let { return it }
        return synchronized(replayLock) {
            replay ?: runCatching { replayFactory(this, mainHandler) }
                .onFailure { SdkLog.w("session replay unavailable", it) }
                .getOrNull()
                ?.also { created ->
                    replay = created
                    resumedActivity?.get()?.let(created::onActivityResumed)
                }
        }
    }

    /** Test/diagnostic view of the replay controller (null until enabled or started). */
    @VisibleForTesting
    internal val replayController: ReplayController? get() = replay

    /** True between the process' ON_START and ON_STOP. */
    val isForeground: Boolean get() = foreground

    /**
     * `LifecycleRegistry` of the process owner only accepts calls on the main thread, so off the main thread the
     * registration is posted there. [lifecycleStopped] keeps a late post from registering after [shutdown].
     */
    private fun registerProcessObserver() {
        onMain {
            if (!lifecycleStopped) ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(block).onFailure { SdkLog.w("main-thread lifecycle work failed", it) }
        } else {
            mainHandler.post { runCatching(block).onFailure { SdkLog.w("main-thread lifecycle work failed", it) } }
        }
    }

    @Volatile
    private var lifecycleStopped = false

    /**
     * Runs on [scope] after queue maintenance and with [pipeline] ready: recovers the crash files of earlier runs
     * into the queue, then reports the ANR / native crash / low-memory exits of earlier processes (API 30+).
     * Either one finding something sets [leftoverItems], so the caller flushes right away.
     */
    internal fun onBackgroundInit() {
        crashStore.prepare()
        // The redactor compiles its regexes on first use; do that now rather than while a process is dying.
        runCatching { AttributeRedactor.redactString("warm-up 1 a@b.co") }
        crashStore.deleteStaleTemp(clock.nowMs())
        if (killSwitch.optedOut) {
            // The user opted out after the crash: nothing recorded earlier may be sent.
            crashStore.pending().forEach(crashStore::delete)
            collectExitInfo()
            return
        }
        if (CrashRecovery.recover(crashStore, queue) > 0) leftoverItems = true
        collectExitInfo()
    }

    /**
     * Exit reasons go through the normal pipeline (beforeSend, redaction, the attribute budget). While the SDK is
     * disabled nothing is emitted, but the processed timestamp still advances so those exits are not reported
     * after a later opt-in.
     */
    private fun collectExitInfo() {
        runCatching {
            val collector = ExitInfoCollector(app, transportPrefs, items, clock)
            if (isDisabled()) {
                collector.markProcessed()
                return@runCatching
            }
            val exits = collector.collect()
            exits.forEach(::emit)
            if (exits.isNotEmpty()) leftoverItems = true
        }.onFailure { SdkLog.w("collecting exit reasons failed", it) }
    }

    /** Runs inside the crash handler: lock-free reads only, no coroutines, database or network. */
    private fun buildCrashItem(thread: Thread, t: Throwable): Pair<String, String> =
        CrashItemBuilder.build(
            thread = thread,
            t = t,
            nowMs = clock.nowMs(),
            sessionId = crashSessionId,
            userId = identity.userId,
            appVersion = crashAppVersion,
            screen = screens.currentScreen,
            breadcrumbs = breadcrumbs,
        )

    /** Returns true when items from an earlier run are waiting. Runs before the pipeline consumer ever starts. */
    private fun maintainQueueAndSaveTransportConfig(): Boolean {
        // Only here: a batch still marked in-flight belongs to a dead process (never reset it elsewhere).
        runCatching { queue.resetOrphanedBatches() }
        runCatching { queue.purgeOlderThan(clock.nowMs() - RETENTION_MS) }
        // Only when replay data exists: touching the lazy store would create the database. The flusher is created too
        // (it registers itself in FlusherRegistry), so the background worker reuses it instead of building a second one.
        if (replayDatabaseExists()) runCatching { replayFlusher }.onFailure { SdkLog.w("opening replay store failed", it) }
        runCatching {
            TransportConfigStore(transportPrefs).save(
                endpoint = config.endpoint,
                apiKey = config.apiKey,
                environment = config.environment,
                appVersion = device.appVersion,
            )
            crashAppVersion = device.appVersion
        }.onFailure { SdkLog.w("saving transport config failed", it) }
        return runCatching { queue.pendingCount() > 0 }.getOrDefault(false)
    }

    // region public operations (called by the facade)

    fun track(name: String, properties: Map<String, Any?>) {
        if (isDisabled()) return
        if (name.isBlank()) {
            SdkLog.w("track() ignored: name is blank")
            return
        }
        breadcrumbs.add("event", name)
        emit(items.event(name, snapshot(properties)))
    }

    fun screen(name: String) {
        if (isDisabled()) return
        if (name.isBlank()) {
            SdkLog.w("screen() ignored: name is blank")
            return
        }
        screens.onScreen(name, "manual")
    }

    /** Facade entry for `ARTracker.trackNavController`; a no-op without the Navigation dependency. */
    fun trackNavController(navController: Any) {
        if (isDisabled()) return
        navTracker.track(navController)
    }

    fun log(level: LogLevel, message: String, attributes: Map<String, Any?>) {
        if (isDisabled()) return
        breadcrumbs.add("log", message, mapOf("level" to level.wire))
        emit(items.log(level, "log", message, snapshot(attributes)))
    }

    fun identify(userId: String, traits: Map<String, Any?>) {
        if (userId.isBlank()) {
            SdkLog.w("identify() ignored: userId is blank")
            return
        }
        if (isDisabled()) return
        val copy = snapshot(traits)
        // Set first so the `$identify` item itself already carries the user id.
        identity.userId = userId
        identity.traits = copy
        emit(items.event("\$identify", copy))
    }

    /**
     * Handled exception: `log`/`error` item named `exception` with the stack trace (cause chain, max 16 KB), a
     * fingerprint, the breadcrumbs and the current screen. The same fingerprint is reported at most 10 times
     * per session. The item passes through the pipeline, so redaction and the 24 KB attribute budget apply.
     */
    fun captureException(throwable: Throwable, attributes: Map<String, Any?>) {
        if (isDisabled()) return
        val fingerprint = Stacktrace.fingerprint(throwable)
        val summary = Stacktrace.summary(throwable)
        // Evaluated for its side effect too: it records the session id for the crash handler.
        val sessionId = session.currentId().also { crashSessionId = it }
        if (!errorDeduper.shouldReport(sessionId, fingerprint)) return
        val attrs = LinkedHashMap<String, Any?>(snapshot(attributes))
        attrs["exception_class"] = throwable.javaClass.name
        attrs["stacktrace"] = Stacktrace.format(throwable)
        attrs["fingerprint"] = fingerprint
        attrs["handled"] = true
        attrs["breadcrumbs"] = breadcrumbs.snapshot()
        screens.currentScreen?.let { attrs["screen"] = it }
        // Added after the snapshot: the trail attached to this error ends before it, later errors see it.
        breadcrumbs.add("error", summary)
        emit(items.log(LogLevel.ERROR, "exception", summary, attrs))
    }

    fun flush() {
        if (isDisabled()) return
        pipeline.flushNow()
        kickReplayIfAny()
    }

    private fun replayDatabaseExists(): Boolean = app.getDatabasePath(ReplayStore.DB_NAME).exists()

    /** Uploads queued replay batches, if a replay database exists at all (never creates one). Off the caller's thread. */
    private fun kickReplayIfAny() {
        replayJob = scope.launch {
            runCatching { if (replayDatabaseExists()) replayKicker.kick() }
                .onFailure { SdkLog.w("starting the replay upload failed", it) }
        }
    }

    /** `ARTracker.startReplay()`: records regardless of `replay.enabled` and sampling (never while disabled). */
    fun startReplay() {
        if (isDisabled()) return
        ensureReplay()?.start(manual = true)
    }

    /** `ARTracker.stopReplay()`: flushes and stops recording until the next [startReplay]. */
    fun stopReplay() {
        replay?.stop()
    }

    /** Logout: forgets the user and starts a new session. Works while disabled (clearing is always safe). */
    fun reset() {
        endedSessionUserId = identity.userId
        try {
            identity.reset()
            session.rotate()
        } finally {
            endedSessionUserId = null
        }
    }

    /**
     * Opt-out is held by [killSwitch], the single source of truth that the facade, the flusher and the timer all
     * consult: the in-memory flag flips synchronously, so no later flush or track can slip through.
     *
     * Persistence (a SharedPreferences `commit()`, i.e. disk I/O): off the main thread it is written before this
     * call returns, so the choice survives a process death right afterwards; on the main thread it is queued on
     * [scope] instead (a crash in that short window can lose it). Opting out also stops the timer and DISCARDS all
     * queued data (database rows and items still in the pipeline), so nothing recorded earlier is sent later,
     * even after opting back in. Opting back in resumes normally.
     */
    fun optOut(enabled: Boolean) {
        killSwitch.applyOptedOut(enabled)
        runCatching { replay?.onDisabledChanged() }
        if (enabled) {
            pipeline.stopTimer()
            pipeline.discardAll()
            // Nothing recorded earlier may be sent later: the buffered samples go too.
            stopPerfLoop()
            runCatching { perf?.discard() }
            runCatching { perf?.onBackground() } // stops the frame source; its flush finds nothing
            clearReplayBatches()
        } else if (foreground) {
            pipeline.startTimer()
            startPerfLoop()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            persistJob = scope.launch { persistOptOut() }
        } else {
            persistOptOut()
        }
    }

    /** Opting out also discards the replay batches waiting for upload (the open batch is dropped by the controller). */
    private fun clearReplayBatches() {
        replayJob = scope.launch {
            runCatching { if (replayDatabaseExists()) replayQueue.clear() }
                .onFailure { SdkLog.w("clearing replay batches failed", it) }
        }
    }

    private fun persistOptOut() {
        runCatching { killSwitch.persistOptedOut() }.onFailure { SdkLog.w("persisting opt-out failed", it) }
    }

    /**
     * Not for the main thread: the pending count is a database query. Meant for debug screens and tests.
     * (A cached value was rejected because it would be stale and make the number untrustworthy.)
     */
    fun diagnostics(): Diagnostics = Diagnostics(
        initialized = true,
        sessionId = runCatching { session.currentId() }.getOrNull(),
        pendingItems = runCatching { queue.pendingCount() }.getOrDefault(0),
        lastSendResult = flusher.lastResult,
        disabled = isDisabled(),
    )

    // endregion

    // region lifecycle hooks (driven by [processObserver])

    internal fun onForeground() {
        foreground = true
        // May rotate the session after a long background: that emits session_end/session_start via the listener.
        session.onForeground()
        runCatching { replay?.onAppForeground() } // checks the kill-switch itself
        if (isDisabled()) return
        pipeline.startTimer()
        emit(items.event("app_foreground"))
        startPerfLoop()
    }

    internal fun onBackground() {
        foreground = false
        // Stops capture, unwraps windows and flushes the open replay chunk: nothing replay-related runs in background.
        runCatching { replay?.onAppBackground() }
        val active = !isDisabled()
        stopPerfLoop() // no sampling in the background
        if (active) emit(items.event("app_background"))
        // Enqueued before flushNow(): the last samples travel with this flush, not with the next launch.
        runCatching { perf?.onBackground() }
        pipeline.stopTimer()
        if (active) {
            pipeline.flushNow()
            kickReplayIfAny()
            scope.launch { scheduler.scheduleFlush() }
        }
        session.onBackground()
    }

    // endregion

    /** True while the user opted out or the 401 kill-switch is tripped. Cheap: in-memory prefs reads. */
    fun isDisabled(): Boolean = killSwitch.isActive()

    private fun emit(item: TelemetryItem) {
        pipeline.enqueue(item)
    }

    private fun itemsFor(sessionId: String, userId: String?) = ItemFactory(clock, { sessionId }, { userId })

    /** Shallow copy taken on the caller's thread so later mutation of the caller's map cannot race the pipeline. */
    private fun snapshot(map: Map<String, Any?>): Map<String, Any?> =
        if (map.isEmpty()) emptyMap() else LinkedHashMap(map)

    /** Awaits queued persistence/opt-out writes and the pipeline: deterministic tests without sleeping. */
    @VisibleForTesting
    suspend fun awaitIdle() {
        persistJob?.join()
        backgroundInitJob?.join()
        replayJob?.join()
        pipeline.awaitIdle()
    }

    /** Cancels everything this instance started. Used by `ARTracker.resetForTest()`. */
    fun shutdown() {
        lifecycleStopped = true
        killSwitch.onTrip = null
        onMain { mainHandler.removeCallbacks(resumeAfterTrip) }
        runCatching { app.unregisterActivityLifecycleCallbacks(activityTracker) }
        runCatching { activityTracker.detachAll() }
        runCatching { navTracker.clear() }
        runCatching { replay?.shutdown() }
        stopPerfLoop()
        runCatching { memoryCallbacks?.let(app.applicationContext::unregisterComponentCallbacks) }
        runCatching { perf?.let { it.discard(); it.onBackground() } }
        onMain { ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver) }
        runCatching { pipeline.stopTimer() }
        runCatching { crashHandler?.uninstall() }
        scope.cancel()
        if (FlusherRegistry.current === flusher) FlusherRegistry.current = null
        if (replayFlusherLazy.isInitialized() && FlusherRegistry.replay === replayFlusherLazy.value) FlusherRegistry.replay = null
        if (replayStoreLazy.isInitialized()) replayStoreLazy.value.close()
        queue.close()
    }

    /** A map whose entries are computed on first access, which happens on the pipeline thread. */
    private class LazyAttributes(supplier: () -> Map<String, Any?>) : AbstractMap<String, Any?>() {
        private val delegate: Map<String, Any?> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            runCatching(supplier).getOrDefault(emptyMap())
        }
        override val entries: Set<Map.Entry<String, Any?>> get() = delegate.entries
    }

    companion object {
        private const val SESSION_PREFS = "artracker_session"
        private const val IDENTITY_PREFS = "artracker_identity"
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val RESUME_SLACK_MS = 1_000L
        private const val CRASH_DIR = "artracker/crash"

        @Volatile
        private var instance: ARTrackerCore? = null

        fun instanceOrNull(): ARTrackerCore? = instance

        internal fun install(core: ARTrackerCore) {
            instance = core
        }

        internal fun clear() {
            instance = null
        }
    }
}
