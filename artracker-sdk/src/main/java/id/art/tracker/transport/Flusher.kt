package id.art.tracker.transport

import id.art.tracker.core.Clock
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SdkLog
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

    /** Epoch millis before which no request is made. */
    @Volatile
    var pausedUntilMs: Long = 0L
        private set

    /**
     * Short human-readable description of the last meaningful outcome, for diagnostics: `accepted`,
     * `http_<status>_dropped`, `too_large_dropped`, `encode_failed_dropped`, `unauthorized`, `disabled`,
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
        val now = clock.nowMs()
        if (now < pausedUntilMs) return stop(FlushOutcome.PAUSED)

        val batch = queue.claimBatch(maxItems = claimCap) ?: run {
            claimCap = MAX_BATCH_ITEMS
            return stop(FlushOutcome.EMPTY)
        }

        val body = try {
            encoder.encodeGzip(batch.items, now)
        } catch (e: Exception) {
            // Encoding is deterministic: retrying the same items would fail forever and block the queue.
            SdkLog.w("encoding batch failed; dropping ${batch.items.size} items", e)
            queue.complete(batch.id)
            lastResult = "encode_failed_dropped"
            return Step(FlushOutcome.DROPPED, proceed = true)
        }

        val result = try {
            sender.send(body)
        } catch (e: Exception) {
            SendResult.NetworkError(e)
        }

        return when (result) {
            SendResult.Accepted -> {
                queue.complete(batch.id)
                failures = 0
                lastResult = "accepted"
                Step(FlushOutcome.SENT, proceed = true)
            }
            is SendResult.Drop -> {
                queue.complete(batch.id)
                lastResult = "http_${result.status}_dropped"
                Step(FlushOutcome.DROPPED, proceed = true)
            }
            SendResult.Unauthorized -> {
                queue.release(batch.id)
                killSwitch.trip()
                lastResult = "unauthorized"
                stop(FlushOutcome.DISABLED)
            }
            SendResult.TooLarge -> tooLarge(batch)
            is SendResult.RetryAfter -> {
                queue.release(batch.id)
                pauseFor(now, result.delayMs ?: backoff.delayFor(failures))
                failures++
                lastResult = "retry_after"
                stop(FlushOutcome.PAUSED)
            }
            is SendResult.NetworkError -> {
                queue.release(batch.id)
                pauseFor(now, backoff.delayFor(failures))
                failures++
                lastResult = "network_error"
                scheduler.scheduleFlush()
                stop(FlushOutcome.FAILED)
            }
        }
    }

    /**
     * 413: a single item can never fit, so it is discarded; a larger batch goes back to the queue and the next
     * claim takes half as many items. [FlushOutcome.FAILED] is returned for the split case (nothing delivered),
     * but [flushAll] keeps going because no pause was set and a retry is expected to succeed.
     */
    private fun tooLarge(batch: Batch): Step {
        if (batch.items.size <= 1) {
            queue.complete(batch.id)
            lastResult = "too_large_dropped"
            return Step(FlushOutcome.DROPPED, proceed = true)
        }
        queue.release(batch.id)
        claimCap = (batch.items.size / 2).coerceAtLeast(1)
        lastResult = "too_large_split"
        return Step(FlushOutcome.FAILED, proceed = true)
    }

    private fun pauseFor(now: Long, delayMs: Long) {
        pausedUntilMs = now + delayMs.coerceAtLeast(0L)
    }

    private companion object {
        const val MAX_BATCH_ITEMS = 50
    }
}
