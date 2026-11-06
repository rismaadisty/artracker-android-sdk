package id.art.tracker

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.Clock
import id.art.tracker.core.SdkLog
import id.art.tracker.transport.FlusherRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * Public entry point of the ARTracker SDK.
 *
 * - Call [init] once from `Application.onCreate()`.
 * - Every function is safe to call from any thread, never throws and never blocks on I/O. Before [init] (or after
 *   an invalid configuration) they are no-ops; while the SDK is disabled (user opt-out, or the server rejected the
 *   API key) tracking calls are no-ops too.
 */
public object ARTracker {
    private val initLock = Any()
    private val lastNotInitializedWarningMs = AtomicLong(0)

    /**
     * Initialises the SDK. Cheap on the calling thread: it validates the config, builds the object graph and
     * starts the first session. Opening the database, purging old items and the first flush happen in the
     * background. A second call is ignored; an invalid [config] is reported through the debug log and leaves the
     * SDK uninitialised.
     */
    @JvmStatic
    public fun init(context: Context, config: ARTrackerConfig) {
        runCatching { doInit(context, config) }.onFailure { SdkLog.w("init failed", it) }
    }

    /** Associates later items with [userId] and records a `$identify` event carrying [traits]. */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?> = emptyMap()) {
        withCore("identify") { it.identify(userId, traits) }
    }

    /** Records a custom event. */
    @JvmStatic
    @JvmOverloads
    public fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        withCore("track") { it.track(name, properties) }
    }

    /** Records a `screen_view` event for a screen that is not tracked automatically (e.g. Compose). */
    @JvmStatic
    public fun screen(name: String) {
        withCore("screen") { it.screen(name) }
    }

    /**
     * Records a `screen_view` (type `compose`) for every destination change of a Jetpack Navigation
     * `NavController`. Typed `Any` so the SDK does not need Navigation on the classpath: without it (or for any
     * other argument) the call is ignored. Tracking the same controller twice has no extra effect.
     */
    @JvmStatic
    public fun trackNavController(navController: Any) {
        withCore("trackNavController") { it.trackNavController(navController) }
    }

    /** Records a log item at [level]. */
    @JvmStatic
    @JvmOverloads
    public fun log(level: LogLevel, message: String, attributes: Map<String, Any?> = emptyMap()) {
        withCore("log") { it.log(level, message, attributes) }
    }

    /**
     * Records a handled [throwable] as an `error` log item named `exception`, with its stack trace, a fingerprint,
     * the recent breadcrumbs and the current screen attached. At most 10 identical errors are sent per session.
     */
    @JvmStatic
    @JvmOverloads
    public fun captureException(throwable: Throwable, attributes: Map<String, Any?> = emptyMap()) {
        withCore("captureException") { it.captureException(throwable, attributes) }
    }

    /**
     * Asks the SDK to send what is queued. Returns immediately (safe on the main thread); items recorded before
     * this call are included.
     */
    @JvmStatic
    public fun flush() {
        withCore("flush") { it.flush() }
    }

    /** Logout: forgets the identified user and starts a new session. */
    @JvmStatic
    public fun reset() {
        withCore("reset") { it.reset() }
    }

    /**
     * Opts the user out ([enabled] = true) or back in. Takes effect immediately for every part of the SDK:
     * nothing more is recorded or sent, and everything already queued is deleted (it is never sent later, even
     * after opting back in). The choice is persisted: off the main thread it is written before this call
     * returns; on the main thread the write is queued on a background thread.
     */
    @JvmStatic
    public fun optOut(enabled: Boolean) {
        withCore("optOut") { it.optOut(enabled) }
    }

    /**
     * Starts session replay recording for this process, regardless of `ReplayConfig.enabled` and the sample rate
     * (still never while the user opted out). Safe from any thread: the work happens on the main thread.
     */
    @JvmStatic
    public fun startReplay() {
        withCore("startReplay") { it.startReplay() }
    }

    /**
     * Stops session replay recording: what was recorded is sent, and nothing more is recorded until [startReplay]
     * (also across new sessions). Safe from any thread.
     */
    @JvmStatic
    public fun stopReplay() {
        withCore("stopReplay") { it.stopReplay() }
    }

    /** True after a successful [init]. */
    @JvmStatic
    public fun isInitialized(): Boolean = ARTrackerCore.instanceOrNull() != null

    /**
     * Returns a snapshot of the SDK state. [Diagnostics.pendingItems] is read from the database, so do **not**
     * call this on the main thread in production; it is meant for debug screens and tests. Never throws.
     */
    @JvmStatic
    public fun diagnostics(): Diagnostics =
        runCatching { ARTrackerCore.instanceOrNull()?.diagnostics() }.getOrNull()
            ?: Diagnostics(
                initialized = false,
                sessionId = null,
                pendingItems = 0,
                lastSendResult = null,
                disabled = false,
            )

    /** Tears down the singleton so tests can call [init] again. */
    @VisibleForTesting
    internal fun resetForTest() {
        runCatching {
            synchronized(initLock) {
                ARTrackerCore.instanceOrNull()?.shutdown()
                ARTrackerCore.clear()
                FlusherRegistry.current = null
                SdkLog.enabled = false
                lastNotInitializedWarningMs.set(0)
            }
        }
    }

    private fun doInit(context: Context, config: ARTrackerConfig) {
        synchronized(initLock) {
            if (ARTrackerCore.instanceOrNull() != null) {
                SdkLog.w("init() called more than once; ignoring the new configuration")
                return
            }
            SdkLog.enabled = config.debug
            val errors = config.validate()
            if (errors.isNotEmpty()) {
                // The messages describe the problem only; the API key itself is never logged.
                errors.forEach { SdkLog.w("invalid configuration: $it") }
                return
            }
            val app = context.applicationContext as? Application
            if (app == null) {
                SdkLog.w("init() needs a context that has an Application; ignoring")
                return
            }
            val core = ARTrackerCore(app, config)
            ARTrackerCore.install(core)
            try {
                core.start()
            } catch (t: Throwable) {
                ARTrackerCore.clear()
                core.shutdown()
                throw t
            }
        }
    }

    private inline fun withCore(op: String, block: (ARTrackerCore) -> Unit) {
        try {
            val core = ARTrackerCore.instanceOrNull()
            if (core == null) {
                warnNotInitialized(op)
                return
            }
            block(core)
        } catch (t: Throwable) {
            SdkLog.w("$op failed", t)
        }
    }

    /** At most one warning per minute, so a forgotten `init()` does not flood logcat. */
    private fun warnNotInitialized(op: String) {
        val now = Clock.SYSTEM.nowMs()
        val last = lastNotInitializedWarningMs.get()
        if (now - last >= WARNING_INTERVAL_MS && lastNotInitializedWarningMs.compareAndSet(last, now)) {
            SdkLog.w("ARTracker.$op() called before init(); ignoring")
        }
    }

    private const val WARNING_INTERVAL_MS = 60_000L
}
