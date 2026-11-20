package id.art.tracker.appstart

import android.os.Looper
import id.art.tracker.AppStartConfig
import id.art.tracker.core.SdkLog

/** Reads the stack of the main thread; a fake in tests. */
internal fun interface MainStackSource {
    /** Leaf first, as `Thread.getStackTrace`. May throw or return an empty array. */
    fun read(): Array<StackTraceElement>
}

internal object LooperMainStack : MainStackSource {
    override fun read(): Array<StackTraceElement> = Looper.getMainLooper().thread.stackTrace
}

/** A finished profile: its [buffer], the final [intervalMs] and the mean cost of one sample. */
internal data class ProfileResult(val buffer: StackSampleBuffer, val intervalMs: Long, val meanCostMicros: Long)

internal object ProfileDecision {
    /** Decided once per process, at init: `appStart.enabled && profiling && random < profileSampleRate`. */
    fun shouldProfile(config: AppStartConfig, random: () -> Double = Math::random): Boolean =
        config.enabled && config.profiling && random() < config.profileSampleRate
}

/**
 * Samples the main thread stack on the thread `artracker-startup-sampler` while the app starts: every
 * [IntervalPolicy.intervalMs] (10 ms, backing off to 40 when a sample costs more than 1 ms on average), from [start] until
 * [stop] or [discard], or until [maxDurationMs] after the process start, or until the buffer is full.
 *
 * Nothing escapes the thread: a stack read that throws or comes back empty is skipped and sampling goes on. [stop] and
 * [discard] set a flag and interrupt the sleep; the buffer is handed over only after the thread has ended.
 */
internal class StartupProfiler(
    private val source: MainStackSource,
    private val uptime: () -> Long,
    private val nanoTime: () -> Long,
    private val processStartUptimeMs: Long,
    private val maxDurationMs: Long = 10_000,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val buffer = StackSampleBuffer()
    private val policy = IntervalPolicy()

    @Volatile private var stopped = false

    @Volatile private var discarded = false

    @Volatile private var thread: Thread? = null
    private var totalCostNanos = 0L
    private var reads = 0L

    fun start() {
        val t = Thread({ runLoop() }, THREAD_NAME)
        t.isDaemon = true
        t.priority = Thread.NORM_PRIORITY
        thread = t
        t.start()
    }

    /** Stops and returns the profile; null when nothing was sampled, it was discarded, or the thread did not end in time. */
    fun stop(): ProfileResult? {
        stopped = true
        val t = thread
        if (t != null) {
            t.interrupt()
            try {
                t.join(JOIN_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (t.isAlive) return null
        }
        if (discarded || buffer.sampleCount == 0) return null
        return ProfileResult(buffer, policy.intervalMs, if (reads == 0L) 0L else totalCostNanos / reads / NANOS_PER_MICRO)
    }

    /** Stops and throws the samples away; a later [stop] returns null. */
    fun discard() {
        discarded = true
        stopped = true
        thread?.interrupt()
    }

    @androidx.annotation.VisibleForTesting
    internal fun runLoopForTest() = runLoop()

    @androidx.annotation.VisibleForTesting
    internal fun sampleCountForTest(): Int = buffer.sampleCount

    private fun runLoop() {
        try {
            while (!stopped && uptime() - processStartUptimeMs < maxDurationMs) {
                val c0 = nanoTime()
                val stack = try {
                    source.read()
                } catch (t: Throwable) {
                    null
                }
                val cost = nanoTime() - c0
                totalCostNanos += cost
                reads++
                policy.record(cost)
                if (stack != null && stack.isNotEmpty() && !buffer.add(uptime() - processStartUptimeMs, stack)) break // full
                try {
                    sleep(policy.intervalMs)
                } catch (e: InterruptedException) {
                    break // stop() or discard() woke the sleep
                }
            }
        } catch (t: Throwable) {
            SdkLog.w("the startup sampler stopped", t)
        }
    }

    private companion object {
        const val THREAD_NAME = "artracker-startup-sampler"
        const val JOIN_MS = 50L
        const val NANOS_PER_MICRO = 1_000L
    }
}
