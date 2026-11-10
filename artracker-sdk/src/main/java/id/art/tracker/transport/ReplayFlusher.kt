package id.art.tracker.transport

import id.art.tracker.core.Clock
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SdkLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Uploads queued replay batches, one at a time, oldest first. Same structure and guarantees as [Flusher] for the ingest
 * queue: each batch is processed under one lock (so at most one upload is ever in flight), a claimed batch is always
 * settled (completed or released), and [Error]s and cancellation propagate after the batch is released. The three
 * HTTP steps are idempotent on the server, so every failure simply releases the batch and the next attempt repeats them.
 *
 * Callers must not invoke this from the main thread: the sender blocks on HTTP.
 */
internal class ReplayFlusher(
    private val queue: ReplayQueue,
    private val sender: ReplaySender,
    private val killSwitch: KillSwitch,
    private val clock: Clock,
    private val backoff: Backoff,
    private val scheduler: BackgroundScheduler,
    /** The server said screenshot replay is off (`403 screenshot_disabled`); queued screenshot chunks are already gone. */
    private val onScreenshotDisabled: () -> Unit = {},
) {
    private val lock = ReentrantLock()

    // Guarded by [lock].
    private var failures = 0
    private var settled = false

    /** Epoch millis before which no upload is attempted. */
    @Volatile
    var pausedUntilMs: Long = 0L
        private set

    /**
     * Short description of the last meaningful outcome, for diagnostics: `replay_uploaded`,
     * `replay_dropped_<reason>`, `replay_screenshot_disabled`, `replay_unauthorized`, `replay_retry`, `replay_network_error`, `replay_disabled`.
     * Idle (`EMPTY`) and still-paused calls leave it unchanged so the cause of the pause stays visible.
     */
    @Volatile
    var lastResult: String? = null
        private set

    /** Uploads at most one batch. */
    fun flushOnce(): FlushOutcome = lock.withLock { step().outcome }

    /**
     * Drains the queue, at most [maxUploadsPerSecond] uploads per second, until it is empty or a batch ends with an
     * outcome other than SENT / DROPPED. Returns [FlushOutcome.EMPTY] once the queue is drained, otherwise the outcome
     * that stopped the loop. The lock is released between batches, so other callers can interleave.
     */
    suspend fun flushAll(maxUploadsPerSecond: Int = 2): FlushOutcome {
        val intervalMs = 1_000L / maxUploadsPerSecond.coerceAtLeast(1)
        while (true) {
            val step = lock.withLock { step() }
            if (!step.proceed) return step.outcome
            if (queue.pendingCount() == 0) return FlushOutcome.EMPTY
            delay(intervalMs)
        }
    }

    private class Step(val outcome: FlushOutcome, val proceed: Boolean = false)

    private fun stop(outcome: FlushOutcome) = Step(outcome, proceed = false)

    private fun step(): Step {
        if (killSwitch.isActive()) {
            lastResult = "replay_disabled"
            return stop(FlushOutcome.DISABLED)
        }
        if (clock.nowMs() < pausedUntilMs) return stop(FlushOutcome.PAUSED)

        val upload = queue.claim() ?: return stop(FlushOutcome.EMPTY)

        // From here on the batch is in-flight and must be settled exactly once.
        settled = false
        try {
            return process(upload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. a SQLite failure in complete/release, or a throwing kill-switch store.
            SdkLog.w("replay flush step failed", e)
            lastResult = "replay_queue_error"
            return stop(FlushOutcome.FAILED)
        } finally {
            if (!settled) {
                try {
                    queue.release(upload.id)
                } catch (e: Throwable) {
                    // Must not mask the original failure; resetOrphaned() recovers it on next start.
                    SdkLog.w("releasing replay batch failed", e)
                }
            }
        }
    }

    private fun process(upload: ReplayUpload): Step {
        val result = try {
            sender.upload(upload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReplayResult.NetworkError(e)
        }

        return when (result) {
            ReplayResult.Done -> {
                complete(upload)
                failures = 0
                lastResult = "replay_uploaded"
                Step(FlushOutcome.SENT, proceed = true)
            }
            is ReplayResult.Drop -> {
                complete(upload)
                lastResult = "replay_dropped_${result.reason}"
                Step(FlushOutcome.DROPPED, proceed = true)
            }
            ReplayResult.ScreenshotDisabled -> {
                complete(upload)
                queue.clearFormat(ReplayFormat.SCREEN_V1)
                lastResult = "replay_screenshot_disabled"
                runCatching(onScreenshotDisabled).onFailure { SdkLog.w("screenshot-disabled handler failed", it) }
                Step(FlushOutcome.DROPPED, proceed = true)
            }
            ReplayResult.Unauthorized -> {
                // Trip first so a failing release() cannot skip the kill-switch.
                killSwitch.trip()
                lastResult = "replay_unauthorized"
                release(upload)
                stop(FlushOutcome.DISABLED)
            }
            is ReplayResult.Retry -> {
                release(upload)
                pauseFor(result.delayMs ?: backoff.delayFor(failures))
                failures++
                lastResult = "replay_retry"
                stop(FlushOutcome.PAUSED)
            }
            is ReplayResult.NetworkError -> {
                release(upload)
                pauseFor(backoff.delayFor(failures))
                failures++
                lastResult = "replay_network_error"
                scheduler.scheduleFlush()
                stop(FlushOutcome.FAILED)
            }
        }
    }

    private fun complete(upload: ReplayUpload) {
        queue.complete(upload.id)
        settled = true
    }

    private fun release(upload: ReplayUpload) {
        queue.release(upload.id)
        settled = true
    }

    /** Reads the clock again: the upload may have taken a while since the pre-send check. */
    private fun pauseFor(delayMs: Long) {
        pausedUntilMs = clock.nowMs() + delayMs.coerceAtLeast(0L)
    }
}

/**
 * Runs [ReplayFlusher.flushAll] in the background whenever a batch is queued. One drain runs at a time; a [kick] while
 * one is running makes it run once more when it finishes (the new batch may have arrived after its last claim).
 */
internal class ReplayKicker(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    private val flusher: ReplayFlusher,
) {
    private val running = AtomicBoolean(false)
    private val rerun = AtomicBoolean(false)

    fun kick() {
        rerun.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch(context) {
            try {
                while (rerun.getAndSet(false)) {
                    flusher.flushAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SdkLog.w("replay drain failed (${e.javaClass.simpleName})")
            } finally {
                running.set(false)
                // A kick that landed between the last check and the reset must not be lost.
                if (rerun.get()) kick()
            }
        }
    }
}
