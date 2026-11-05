package id.art.tracker.transport

import androidx.annotation.VisibleForTesting
import id.art.tracker.BeforeSend
import id.art.tracker.TelemetryItem
import id.art.tracker.core.Clock
import id.art.tracker.core.SdkLog
import id.art.tracker.privacy.AttributeRedactor
import id.art.tracker.priority
import id.art.tracker.toJson
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * Off-main-thread pipeline from `track()` to the persistent queue to the network (ARCHITECTURE.md sections 4.4,
 * 9.2 and 9.4).
 *
 * - [enqueue] only does a `trySend` into a bounded channel (capacity 1000, oldest dropped on overflow), so the
 *   caller is never blocked and nothing here throws.
 * - One consumer coroutine runs `beforeSend`, the attribute redactor (except for `replay_chunk`) and builds the
 *   [StoredItem]s, which it persists in groups of up to [GROUP_SIZE] or [GROUP_WINDOW_MS] (whichever is first),
 *   as a single `queue.insert(list)`. A group holding a priority-0 item (crash/fatal) is persisted at once.
 * - A second coroutine performs flushes. A flush is requested right after persisting a priority-0 item or when
 *   `pendingCount() >= 50`, by [requestFlush], and by the foreground timer ([startTimer]).
 *
 * [Flusher] blocks its thread (lock + HTTP). Pass a [scope] with a background dispatcher, never the main thread.
 * If that dispatcher is single-threaded, also pass an IO [flushContext] so a request in flight does not stall the
 * consumer (flushes then run under `withContext(flushContext)`).
 */
internal class EventPipeline(
    private val scope: CoroutineScope,
    private val queue: ItemQueue,
    private val flusher: Flusher,
    private val clock: Clock,
    private val beforeSend: BeforeSend?,
    private val flushIntervalMs: Long,
    private val flushContext: CoroutineContext = EmptyCoroutineContext,
) {
    /** Idle accounting: [settled] counts items fully handled (persisted, dropped, or evicted from the channel). */
    private data class Progress(val settled: Long = 0, val flushes: Int = 0, val stopped: Boolean = false)

    private val progress = MutableStateFlow(Progress())
    private val accepted = AtomicLong(0)

    private val channel = Channel<TelemetryItem>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        // Items evicted by DROP_OLDEST never reach the consumer; they still count as handled for awaitIdle.
        onUndeliveredElement = { settle(1) },
    )

    /** Wakes the consumer to persist its partial group (group window elapsed, or [awaitIdle]). */
    private val nudge = Channel<Unit>(Channel.CONFLATED)
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val flushPending = AtomicBoolean(false)

    private val timerLock = Any()
    private var timerJob: Job? = null

    init {
        val consumer = scope.launch { consume() }
        val flushLoop = scope.launch { flushLoop() }
        // If the scope dies, nobody will make progress any more: release awaitIdle() callers.
        consumer.invokeOnCompletion { progress.update { it.copy(stopped = true) } }
        flushLoop.invokeOnCompletion { progress.update { it.copy(stopped = true) } }
    }

    /**
     * Hands [item] to the consumer. Never blocks and never throws. Returns false only if the item could not be
     * accepted at all (channel closed); when the buffer is full the oldest queued item is dropped instead.
     */
    fun enqueue(item: TelemetryItem): Boolean {
        accepted.incrementAndGet()
        return try {
            if (channel.trySend(item).isSuccess) {
                true
            } else {
                settle(1)
                false
            }
        } catch (e: Exception) {
            settle(1)
            false
        }
    }

    /** Starts the foreground flush timer; flushes only when something is pending. Idempotent. */
    fun startTimer() {
        synchronized(timerLock) {
            if (timerJob?.isActive == true) return
            timerJob = scope.launch {
                while (isActive) {
                    delay(flushIntervalMs)
                    try {
                        if (queue.pendingCount() > 0) requestFlush()
                    } catch (e: Exception) {
                        SdkLog.w("flush timer tick failed", e)
                    }
                }
            }
        }
    }

    fun stopTimer() {
        synchronized(timerLock) {
            timerJob?.cancel()
            timerJob = null
        }
    }

    /** Asks for a flush on the pipeline scope. Requests made while one is already queued are merged. */
    fun requestFlush() {
        if (flushPending.compareAndSet(false, true)) {
            progress.update { it.copy(flushes = it.flushes + 1) }
            flushSignal.trySend(Unit)
        }
    }

    /**
     * Suspends until every item enqueued before this call has been persisted (or dropped), and no flush is
     * running or queued. Deterministic: driven by counters, not by sleeping. Returns at once if the pipeline's
     * scope has been cancelled.
     */
    @VisibleForTesting
    suspend fun awaitIdle() {
        val target = accepted.get()
        nudge.trySend(Unit)
        progress.first { it.stopped || (it.settled >= target && it.flushes == 0) }
    }

    private fun settle(count: Int) {
        if (count > 0) progress.update { it.copy(settled = it.settled + count) }
    }

    // region consumer

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun consume() {
        val buffer = ArrayList<StoredItem>(GROUP_SIZE)
        var groupMessages = 0
        var groupTimer: Job? = null

        suspend fun persistGroup() {
            groupTimer?.cancel()
            groupTimer = null
            val count = groupMessages
            groupMessages = 0
            try {
                persist(buffer)
            } finally {
                buffer.clear()
                settle(count)
            }
        }

        while (true) {
            // Channel first: pending items are always drained before a nudge persists the partial group.
            val item = select<TelemetryItem?> {
                channel.onReceive { it }
                nudge.onReceive { null }
            }
            try {
                if (item == null) {
                    if (groupMessages > 0) persistGroup()
                    continue
                }
                if (groupMessages == 0) {
                    groupTimer = scope.launch {
                        delay(GROUP_WINDOW_MS)
                        nudge.trySend(Unit)
                    }
                }
                groupMessages++
                val stored = prepare(item)
                if (stored != null) buffer += stored
                if (groupMessages >= GROUP_SIZE || stored?.priority == 0) persistGroup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never let one bad item or a failing store kill the consumer.
                SdkLog.w("pipeline step failed", e)
            }
        }
    }

    /** beforeSend -> redaction -> serialisation. Null when the item is dropped (by beforeSend, or unserialisable). */
    private fun prepare(original: TelemetryItem): StoredItem? {
        return try {
            val hook = beforeSend
            val item = if (hook == null) {
                original
            } else {
                try {
                    hook.process(original) ?: return null
                } catch (e: Exception) {
                    // Log the failure only, never the item: it may carry sensitive data.
                    SdkLog.w("beforeSend threw ${e.javaClass.simpleName}; keeping the original item")
                    original
                }
            }
            val safe = if (item.name == REPLAY_CHUNK) {
                item
            } else {
                item.copy(attributes = AttributeRedactor.redact(item.attributes))
            }
            StoredItem(
                id = safe.id,
                priority = safe.priority(),
                // Never later than "now": a future timestamp would defeat the 7-day purge.
                createdAtMs = safe.timestampMs.coerceAtMost(clock.nowMs()),
                payloadJson = safe.toJson(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SdkLog.w("dropping item that could not be processed (${e.javaClass.simpleName})")
            null
        }
    }

    private fun persist(items: List<StoredItem>) {
        if (items.isEmpty()) return
        try {
            queue.insert(items.toList())
        } catch (e: Exception) {
            SdkLog.w("persisting ${items.size} items failed", e)
            return
        }
        val urgent = items.any { it.priority == 0 }
        val pending = try {
            queue.pendingCount()
        } catch (e: Exception) {
            0
        }
        if (urgent || pending >= FLUSH_PENDING_THRESHOLD) requestFlush()
    }

    // endregion

    private suspend fun flushLoop() {
        for (signal in flushSignal) {
            // Cleared before running so a request arriving during the flush schedules another pass.
            flushPending.set(false)
            try {
                withContext(flushContext) { flusher.flushAll() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SdkLog.w("flush failed", e)
            } finally {
                progress.update { it.copy(flushes = it.flushes - 1) }
            }
        }
    }

    private companion object {
        const val CHANNEL_CAPACITY = 1000
        const val GROUP_SIZE = 20
        const val GROUP_WINDOW_MS = 1_000L
        const val FLUSH_PENDING_THRESHOLD = 50
        const val REPLAY_CHUNK = "replay_chunk"
    }
}
