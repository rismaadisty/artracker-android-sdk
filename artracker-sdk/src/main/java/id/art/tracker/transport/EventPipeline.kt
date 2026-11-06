package id.art.tracker.transport

import androidx.annotation.VisibleForTesting
import id.art.tracker.BeforeSend
import id.art.tracker.TelemetryItem
import id.art.tracker.core.Clock
import id.art.tracker.core.SdkLog
import id.art.tracker.privacy.AttributeRedactor
import id.art.tracker.isReplayChunk
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
 * - One consumer coroutine runs `beforeSend`, the attribute redactor (except for the SDK's `replay_chunk` session
 *   items, see `isReplayChunk`) and builds the
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
    private data class Progress(
        val settled: Long = 0,
        val flushes: Int = 0,
        val stopped: Boolean = false,
        val discardsHandled: Long = 0,
    )

    /** An item plus its acceptance sequence number, which [discardAll] compares against. */
    private class Queued(val item: TelemetryItem, val seq: Long)

    private val progress = MutableStateFlow(Progress())
    private val accepted = AtomicLong(0)

    /** Items with a sequence number up to this value are dropped, never persisted ([discardAll]). */
    private val discardCutoff = AtomicLong(0)
    private val discardRequested = AtomicLong(0)

    private val channel = Channel<Queued>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        // Items evicted by DROP_OLDEST never reach the consumer; they still count as handled for awaitIdle.
        onUndeliveredElement = { settle(1) },
    )

    /** Wakes the consumer to persist its partial group (group window elapsed, or [awaitIdle]). */
    private val nudge = Channel<Unit>(Channel.CONFLATED)
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val flushPending = AtomicBoolean(false)

    /** Set by [flushNow]; taken by the consumer once it has drained and persisted everything enqueued before. */
    private val drainFlushPending = AtomicBoolean(false)

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
        val seq = accepted.incrementAndGet()
        return try {
            if (channel.trySend(Queued(item, seq)).isSuccess) {
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
                        if (queue.pendingCount() > 0) triggerFlush()
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

    /**
     * Flush that is ordered after everything already enqueued. Never blocks the caller (safe from the main
     * thread): it only flags the request and wakes the consumer, which drains the channel, persists the open
     * group and only then triggers the flush, so the flush sees every row enqueued before this call. Items
     * enqueued afterwards may or may not be included. Repeated calls before the consumer reacts are merged.
     * Use this for `ARTracker.flush()`, app-background and the like.
     */
    fun flushNow() {
        if (drainFlushPending.compareAndSet(false, true)) {
            // Counted immediately so awaitIdle() also waits for the flush this call leads to.
            progress.update { it.copy(flushes = it.flushes + 1) }
            nudge.trySend(Unit)
        }
    }

    /**
     * Drops everything accepted so far (opt-out): items still in the hand-off buffer, the open group and every
     * row already in the queue. Items accepted after this call are unaffected. Never blocks (safe from any
     * thread): the consumer performs the clearing in order, before it handles any later item.
     */
    fun discardAll() {
        discardCutoff.set(accepted.get())
        discardRequested.incrementAndGet()
        nudge.trySend(Unit)
    }

    /** Same as [flushNow]; kept for existing callers. */
    fun requestFlush() = flushNow()

    /** Unordered flush trigger for the consumer (after its own persist) and the timer. Merged while one is queued. */
    private fun triggerFlush() {
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
        val discardTarget = discardRequested.get()
        nudge.trySend(Unit)
        progress.first {
            it.stopped || (it.settled >= target && it.flushes == 0 && it.discardsHandled >= discardTarget)
        }
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
        var discardsDone = 0L

        fun persistGroup() {
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

        // Called before every step that could persist or accept: a pending discard always wins over later work.
        fun checkDiscard() {
            val requested = discardRequested.get()
            if (requested == discardsDone) return
            groupTimer?.cancel()
            groupTimer = null
            val dropped = groupMessages
            groupMessages = 0
            buffer.clear()
            settle(dropped)
            try {
                queue.clear()
            } catch (e: Exception) {
                SdkLog.w("clearing the queue failed", e)
            }
            discardsDone = requested
            progress.update { it.copy(discardsHandled = requested) }
        }

        fun accept(queued: Queued) {
            checkDiscard()
            if (queued.seq <= discardCutoff.get()) {
                settle(1)
                return
            }
            val item = queued.item
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
        }

        while (true) {
            // Channel first: pending items are always drained before a nudge persists the partial group.
            val item = select<Queued?> {
                channel.onReceive { it }
                nudge.onReceive { null }
            }
            try {
                checkDiscard()
                if (item != null) {
                    accept(item)
                    continue
                }
                // Taking the flag first means every item enqueued before flushNow() is visible to tryReceive.
                val flushAfter = drainFlushPending.compareAndSet(true, false)
                try {
                    if (flushAfter) {
                        while (true) {
                            val next = channel.tryReceive().getOrNull() ?: break
                            try {
                                accept(next)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                SdkLog.w("pipeline step failed", e)
                            }
                        }
                    }
                    if (groupMessages > 0) persistGroup()
                } finally {
                    if (flushAfter) {
                        // Trigger first, then release flushNow()'s reservation, so the count never dips to zero.
                        triggerFlush()
                        progress.update { it.copy(flushes = it.flushes - 1) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Never let one bad item or a failing store kill the consumer (Errors included).
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
            val safe = if (item.isReplayChunk()) {
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
        } catch (e: Throwable) {
            // Throwable, not Exception: a hostile attribute value (toString() that throws an Error or overflows the
            // stack) must cost one item, never the consumer coroutine (which would silently end all recording).
            SdkLog.w("dropping item that could not be processed (${e.javaClass.simpleName})")
            null
        }
    }

    private fun persist(items: List<StoredItem>) {
        if (items.isEmpty()) return
        try {
            if (!queue.insert(items.toList())) SdkLog.w("the queue could not store ${items.size} items")
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
        if (urgent || pending >= FLUSH_PENDING_THRESHOLD) triggerFlush()
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
    }
}
