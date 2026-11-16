package id.art.tracker.telemetry

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import id.art.tracker.core.SdkLog

/**
 * Detects a main thread that is stuck, on Android 7-10 (API 24-29), where `ApplicationExitInfo` cannot tell. One daemon
 * thread wakes every [INTERVAL_MS] and posts a tick to the main thread; a tick still not processed [THRESHOLD_MS] later
 * means the main thread has been blocked that long, and [onAnr] gets the duration and the main thread's stack.
 *
 * - One report per episode: the watchdog re-arms only after the main thread has processed a tick.
 * - Never while a debugger is attached, and an episode seen while attached stays unreported even if the debugger
 *   detaches in the middle of the same hang (a developer stopped at a breakpoint).
 * - A watchdog thread that itself wakes more than [LATE_WAKEUP_MS] late (a doze, CPU starvation) cannot tell a stuck
 *   main thread from a sleeping device: the episode is rebased to now instead of being reported.
 * - It runs only while started: the owner starts it in the foreground and stops it in the background, so nothing
 *   lives, and nothing wakes, while the app is not on screen. A restart begins a fresh episode.
 *
 * The round is [checkOnce], called by the thread loop and by tests, with the clock, handler, debugger flag, stack,
 * thread factory and sleep injectable. [onAnr] runs on the watchdog thread and must be safe there.
 */
internal class AnrWatchdog(
    private val mainHandler: Handler,
    private val onAnr: (blockedMs: Long, mainStack: Array<StackTraceElement>) -> Unit,
    private val uptime: () -> Long = SystemClock::uptimeMillis,
    private val isDebuggerConnected: () -> Boolean = Debug::isDebuggerConnected,
    private val mainStack: () -> Array<StackTraceElement> = { Looper.getMainLooper().thread.stackTrace },
    private val threadFactory: (Runnable) -> Thread = { Thread(it, THREAD_NAME).apply { isDaemon = true } },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    @Volatile
    private var pending = false

    @Volatile
    private var postedAt = 0L

    @Volatile
    private var lastCheckAt = 0L

    @Volatile
    private var reported = false

    /** Raised by every start() and stop(): a thread of an older generation leaves without another check. */
    @Volatile
    private var generation = 0

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Runs on the main thread: it got to this tick, so it is not blocked, and a new episode may be reported. */
    private val tick = Runnable {
        pending = false
        reported = false
    }

    val isRunning: Boolean get() = running

    /** Begins a fresh episode on a new thread; a no-op while running. Callable from any thread. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        generation++
        pending = false
        reported = false
        postedAt = 0L
        lastCheckAt = 0L
        val mine = generation
        val t = threadFactory(Runnable { loop(mine) })
        t.priority = Thread.MIN_PRIORITY
        thread = t
        t.start()
    }

    /** Stops the thread and drops the tick still waiting on the main looper; a no-op while stopped. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        generation++
        thread?.interrupt()
        thread = null
        mainHandler.removeCallbacks(tick)
        pending = false
    }

    private fun loop(mine: Int) {
        try {
            while (true) {
                sleep(INTERVAL_MS)
                if (generation != mine) return // stopped (or restarted) while asleep
                checkOnce()
            }
        } catch (_: InterruptedException) {
            // stop() interrupted the sleep
        }
    }

    /** One round: post a tick, or judge the one still unanswered. Never throws. */
    internal fun checkOnce() {
        try {
            val now = uptime()
            if (lastCheckAt != 0L && now - lastCheckAt > LATE_WAKEUP_MS) {
                // This thread was not running for a while (doze, starvation): what looks like a stuck main thread is not.
                lastCheckAt = now
                if (pending) postedAt = now
                return
            }
            lastCheckAt = now
            if (!pending) {
                pending = true
                postedAt = now
                mainHandler.post(tick)
                return
            }
            if (reported || now - postedAt < THRESHOLD_MS) return
            reported = true // one report per episode, whatever happens next
            if (isDebuggerConnected()) return
            onAnr(now - postedAt, mainStack())
        } catch (t: Throwable) {
            SdkLog.w("ANR watchdog round failed", t)
        }
    }

    companion object {
        const val INTERVAL_MS = 1_000L
        const val THRESHOLD_MS = 5_000L
        const val LATE_WAKEUP_MS = 2 * INTERVAL_MS
        const val THREAD_NAME = "artracker-anr-watchdog"
    }
}
