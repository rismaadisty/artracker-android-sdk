package id.art.tracker.transport

import id.art.tracker.core.Clock
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SdkLog
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal enum class FlushOutcome {
    /** Nothing was pending. */
    EMPTY,

    /** A batch was accepted by the server and removed from the queue. */
    SENT,

    /** A batch was permanently rejected (or could not be encoded) and discarded. */
    DROPPED,

    /** Sending is paused (backoff or server `Retry-After`) until [Flusher.pausedUntilMs]. */
    PAUSED,

    /** Kill-switch active (opt-out, or 401/403 tripped it). Nothing was claimed. */
    DISABLED,

    /** The batch was not delivered and went back to the queue (network error, or split after 413). */
    FAILED,
}

/**
 * Orchestrates sending (ARCHITECTURE.md sections 2 and 9.4): claims a batch from the [ItemQueue], encodes it,
 * posts it through the [Sender] and reacts to the [SendResult].
 *
 * Concurrency: the timer, `ARTracker.flush()` and the WorkManager worker may call in from different threads.
 * Each batch is processed under one lock, so at most one request is ever in flight (section 9.5). A second
 * caller blocks until the running batch finishes and then proceeds with its own claim; it never returns early
 * with a stale result. Callers therefore must not invoke this from the main thread.
 *
 * Failure handling: once a batch is claimed it is always settled. Any [Exception] from the queue or kill-switch
 * (not from the sender, which maps to a network error) ends the step as [FlushOutcome.FAILED] with
 * `lastResult = "queue_error"`; [Error]s (e.g. OutOfMemoryError) and cancellation are NOT swallowed: the batch is
 * released and they propagate to the caller.
 *
 * The consecutive-failure counter that feeds [Backoff] lives here ([Backoff] is stateless).
 */
internal class Flusher(
    private val queue: ItemQueue,
    private val encoder: PayloadEncoder,
    private val sender: Sender,
    private val killSwitch: KillSwitch,
    private val clock: Clock,
    private val backoff: Backoff,
    private val scheduler: BackgroundScheduler,
) {
    private val lock = ReentrantLock()

    // Guarded by [lock].
    private var failures = 0
    private var claimCap = MAX_BATCH_ITEMS
    private var settled = false

    /** Epoch millis before which no request is made. */
    @Volatile
    var pausedUntilMs: Long = 0L
        private set

    /**
     * Short human-readable description of the last meaningful outcome, for diagnostics: `accepted`,
     * `http_<status>_dropped`, `too_large_dropped`, `too_large_split`, `encode_failed_dropped`, `queue_error`, `unauthorized`, `disabled`,
     * `retry_after`, `network_error`. Null until the first batch is attempted. Idle (`EMPTY`) and
     * still-paused calls leave it unchanged so the cause of the pause stays visible.
     */
    @Volatile
    var lastResult: String? = null
        private set

    /** Processes at most one batch (one HTTP request). */
    fun flushOnce(): FlushOutcome = lock.withLock { step().outcome }

    /**
     * Drains the queue, at most [maxBatchesPerSecond] batches per second, until it is empty or a batch ends
     * with an outcome other than SENT / DROPPED (a 413 split also keeps going). Returns [FlushOutcome.EMPTY]
     * when the queue was drained, otherwise the outcome that stopped the loop. The lock is released between
     * batches, so other callers can interleave.
     */
    suspend fun flushAll(maxBatchesPerSecond: Int = 5): FlushOutcome {
        val intervalMs = 1_000L / maxBatchesPerSecond.coerceAtLeast(1)
        while (true) {
            val step = lock.withLock { step() }
            if (!step.proceed) return step.outcome
            if (queue.pendingCount() == 0) {
                lock.withLock { claimCap = MAX_BATCH_ITEMS }
                return FlushOutcome.EMPTY
            }
            delay(intervalMs)
        }
    }

    private class Step(val outcome: FlushOutcome, val proceed: Boolean = false)

    private fun stop(outcome: FlushOutcome) = Step(outcome, proceed = false)

    private fun step(): Step {
        if (killSwitch.isActive()) {
            lastResult = "disabled"
            return stop(FlushOutcome.DISABLED)
        }
        if (clock.nowMs() < pausedUntilMs) return stop(FlushOutcome.PAUSED)

        val batch = queue.claimBatch(maxItems = claimCap) ?: run {
            claimCap = MAX_BATCH_ITEMS
            return stop(FlushOutcome.EMPTY)
        }

        // From here on the batch is in-flight and must be settled (completed or released) exactly once.
        settled = false
        try {
            return process(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. a SQLite failure in complete/release, or a throwing kill-switch store. The batch is
            // released in the finally block below; report it and let the next trigger retry.
            SdkLog.w("flush step failed", e)
            lastResult = "queue_error"
            return stop(FlushOutcome.FAILED)
        } finally {
            // Also runs for Errors (e.g. OutOfMemoryError while gzipping), which propagate after this.
            if (!settled) {
                try {
                    queue.release(batch.id)
                } catch (e: Throwable) {
                    // Must not mask the original failure; resetOrphanedBatches() recovers it on next start.
                    SdkLog.w("releasing batch ${batch.id} failed", e)
                }
            }
        }
    }

    private fun process(batch: Batch): Step {
        val body = try {
            encoder.encodeGzip(batch.items, clock.nowMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Encoding is deterministic: retrying the same items would fail forever and block the queue.
            SdkLog.w("encoding batch failed; dropping ${batch.items.size} items", e)
            complete(batch)
            lastResult = "encode_failed_dropped"
            return Step(FlushOutcome.DROPPED, proceed = true)
        }

        val result = try {
            sender.send(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.NetworkError(e)
        }

        return when (result) {
            SendResult.Accepted -> {
                // If complete() throws the batch is released by step(): the server already has it, so this may
                // cause a duplicate, which is acceptable because item ids are stable (server dedupes retries).
                complete(batch)
                failures = 0
                lastResult = "accepted"
                Step(FlushOutcome.SENT, proceed = true)
            }
            is SendResult.Drop -> {
                complete(batch)
                lastResult = "http_${result.status}_dropped"
                Step(FlushOutcome.DROPPED, proceed = true)
            }
            SendResult.Unauthorized -> {
                // Trip first so a failing release() cannot skip the kill-switch.
                killSwitch.trip()
                lastResult = "unauthorized"
                release(batch)
                stop(FlushOutcome.DISABLED)
            }
            SendResult.TooLarge -> tooLarge(batch)
            is SendResult.RetryAfter -> {
                release(batch)
                pauseFor(result.delayMs ?: backoff.delayFor(failures))
                failures++
                lastResult = "retry_after"
                stop(FlushOutcome.PAUSED)
            }
            is SendResult.NetworkError -> {
                release(batch)
                pauseFor(backoff.delayFor(failures))
                failures++
                lastResult = "network_error"
                scheduler.scheduleFlush()
                stop(FlushOutcome.FAILED)
            }
        }
    }

    private fun complete(batch: Batch) {
        queue.complete(batch.id)
        settled = true
    }

    private fun release(batch: Batch) {
        queue.release(batch.id)
        settled = true
    }

    /**
     * 413: a single item can never fit, so it is discarded; a larger batch goes back to the queue and the next
     * claim takes half as many items. [FlushOutcome.FAILED] is returned for the split case (nothing delivered),
     * but [flushAll] keeps going because no pause was set and a retry is expected to succeed.
     */
    private fun tooLarge(batch: Batch): Step {
        if (batch.items.size <= 1) {
            complete(batch)
            lastResult = "too_large_dropped"
            return Step(FlushOutcome.DROPPED, proceed = true)
        }
        release(batch)
        claimCap = (batch.items.size / 2).coerceAtLeast(1)
        lastResult = "too_large_split"
        return Step(FlushOutcome.FAILED, proceed = true)
    }

    /** Reads the clock again: the request may have taken a while since the pre-send check. */
    private fun pauseFor(delayMs: Long) {
        pausedUntilMs = clock.nowMs() + delayMs.coerceAtLeast(0L)
    }

    private companion object {
        const val MAX_BATCH_ITEMS = 50
    }
}
