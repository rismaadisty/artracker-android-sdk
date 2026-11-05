package id.art.tracker.core

import android.app.Application
import android.os.Handler
import android.os.Looper
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
import id.art.tracker.transport.Backoff
import id.art.tracker.transport.EventPipeline
import id.art.tracker.transport.EventStore
import id.art.tracker.transport.Flusher
import id.art.tracker.transport.FlusherRegistry
import id.art.tracker.transport.IngestClient
import id.art.tracker.transport.PayloadEncoder
import id.art.tracker.transport.TransportConfigStore
import id.art.tracker.transport.WorkManagerScheduler
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val clock: Clock = Clock.SYSTEM,
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

    /** Lazy: building it may query the PackageManager, which init must not do on the main thread. */
    val device: DeviceContext by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DeviceContext(app, config.appVersion) }

    /** User id to stamp on the `session_end` of a session that `reset()` is just closing (user already cleared). */
    @Volatile
    private var endedSessionUserId: String? = null

    private val sessionListener = object : SessionManager.Listener {
        override fun onSessionStart(id: String) {
            runCatching {
                if (isDisabled()) return@runCatching
                // Device facts are gathered lazily on the pipeline thread: nothing here may cost the caller.
                val attrs = LazyAttributes { device.sessionStartAttributes() }
                val sessionItems = itemsFor(id, identity.userId)
                emit(sessionItems.session("session_start", attrs))
                // The screen still on display belongs to this new session too (no-op for the very first one).
                screens.onSessionStarted(sessionItems)
            }
        }

        override fun onSessionEnd(id: String, durationMs: Long) {
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

    val items: ItemFactory = ItemFactory(clock, { session.currentId() }, { identity.userId })

    /**
     * Funnel for every `screen_view`. TODO(Task 15): pass `onScreenChanged` to feed the breadcrumb ring buffer.
     * Items are dropped here while the SDK is disabled (opt-out / 401 kill-switch).
     */
    val screens: ScreenTracker = ScreenTracker({ item -> if (!isDisabled()) emit(item) }, items)

    private val activityTracker = ActivityTracker(screens, listener = null) // TODO(Task 23): replay window listener
    private val navTracker = NavTracker(screens)

    /** Hooks of [ProcessLifecycleOwner]; added on the main thread (see [registerProcessObserver]). */
    private val processObserver = AppLifecycleObserver(onForeground = ::onForeground, onBackground = ::onBackground)
    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Handler(Looper.getMainLooper()) }

    /** Cheap to construct: the database is only opened on first use, in the background. */
    private val queue = EventStore(app, clock)

    private val scheduler = WorkManagerScheduler(app)

    val flusher = Flusher(
        queue = queue,
        encoder = PayloadEncoder { device.envelopeContext(config.environment) },
        sender = IngestClient(config.endpoint, config.apiKey),
        killSwitch = killSwitch,
        clock = clock,
        backoff = Backoff(),
        scheduler = scheduler,
    )

    val pipeline: EventPipeline

    @Volatile
    private var foreground = false

    @Volatile
    private var persistJob: Job? = null

    /** Whether the queue already held items from an earlier run; decided before this run enqueues anything. */
    @Volatile
    private var leftoverItems = false

    init {
        // Must be launched BEFORE the pipeline: the scope runs one task at a time in launch order, so the
        // queue maintenance below completes before the consumer or any flush can touch the database.
        scope.launch { leftoverItems = maintainQueueAndSaveTransportConfig() }
        pipeline = EventPipeline(
            scope = scope,
            queue = queue,
            flusher = flusher,
            clock = clock,
            beforeSend = config.beforeSend,
            flushIntervalMs = config.flushIntervalMs,
            flushContext = Dispatchers.IO,
        )
    }

    /**
     * Second half of init, after construction: publishes the flusher, schedules the remaining background work
     * and starts the lifecycle observers. The foreground is entered by [processObserver]: `LifecycleRegistry`
     * replays `ON_START` to a late observer, so when init runs after the first Activity started, [onForeground]
     * still fires exactly once (no manual call, which would double count).
     */
    fun start() {
        FlusherRegistry.current = flusher
        scope.launch {
            runCatching { onBackgroundInit() }.onFailure { SdkLog.w("background init hook failed", it) }
            // Items left by a previous run (including a crash) are sent right away; a fresh queue is not flushed.
            if (leftoverItems) pipeline.flushNow()
        }
        app.registerActivityLifecycleCallbacks(activityTracker)
        registerProcessObserver()
    }

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
     * Extension point that runs on [scope] after queue maintenance and with [pipeline] ready.
     * TODO(Task 15): recover crash files of the previous session. TODO(Task 16): collect ApplicationExitInfo.
     */
    internal fun onBackgroundInit() = Unit

    /** Returns true when items from an earlier run are waiting. Runs before the pipeline consumer ever starts. */
    private fun maintainQueueAndSaveTransportConfig(): Boolean {
        // Only here: a batch still marked in-flight belongs to a dead process (never reset it elsewhere).
        runCatching { queue.resetOrphanedBatches() }
        runCatching { queue.purgeOlderThan(clock.nowMs() - RETENTION_MS) }
        runCatching {
            TransportConfigStore(transportPrefs).save(
                endpoint = config.endpoint,
                apiKey = config.apiKey,
                environment = config.environment,
                appVersion = device.appVersion,
            )
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
     * TODO(Task 15): replaced by the telemetry pipeline (stack trace limits, fingerprint dedupe, breadcrumbs).
     * Until then: a basic `log`/`error` item named `exception`.
     */
    fun captureException(throwable: Throwable, attributes: Map<String, Any?>) {
        if (isDisabled()) return
        val attrs = LinkedHashMap<String, Any?>(snapshot(attributes))
        attrs["exception_class"] = throwable.javaClass.name
        attrs["stacktrace"] = stackTraceOf(throwable)
        emit(items.log(LogLevel.ERROR, "exception", "${throwable.javaClass.name}: ${throwable.message}", attrs))
    }

    fun flush() {
        if (isDisabled()) return
        pipeline.flushNow()
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
        if (enabled) {
            pipeline.stopTimer()
            pipeline.discardAll()
        } else if (foreground) {
            pipeline.startTimer()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            persistJob = scope.launch { persistOptOut() }
        } else {
            persistOptOut()
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
        if (isDisabled()) return
        pipeline.startTimer()
        emit(items.event("app_foreground"))
    }

    internal fun onBackground() {
        foreground = false
        val active = !isDisabled()
        if (active) emit(items.event("app_background"))
        pipeline.stopTimer()
        if (active) {
            pipeline.flushNow()
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

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString().take(MAX_STACKTRACE_CHARS)
    }

    /** Awaits queued persistence/opt-out writes and the pipeline: deterministic tests without sleeping. */
    @VisibleForTesting
    suspend fun awaitIdle() {
        persistJob?.join()
        pipeline.awaitIdle()
    }

    /** Cancels everything this instance started. Used by `ARTracker.resetForTest()`. */
    fun shutdown() {
        lifecycleStopped = true
        runCatching { app.unregisterActivityLifecycleCallbacks(activityTracker) }
        runCatching { activityTracker.detachAll() }
        runCatching { navTracker.clear() }
        onMain { ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver) }
        runCatching { pipeline.stopTimer() }
        scope.cancel()
        if (FlusherRegistry.current === flusher) FlusherRegistry.current = null
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
        private const val MAX_STACKTRACE_CHARS = 16 * 1024

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
