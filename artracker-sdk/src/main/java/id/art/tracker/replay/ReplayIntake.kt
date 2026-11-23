package id.art.tracker.replay

import id.art.tracker.core.SdkLog
import id.art.tracker.transport.NewReplayBatch
import id.art.tracker.transport.ReplayQueue

/** Receives each closed replay chunk (the core queues it for upload). */
internal fun interface ReplaySink {
    fun accept(batch: NewReplayBatch)

    /** Makes the session's held (buffer-mode) chunks uploadable. */
    fun promote(sessionId: String) {}

    /** Drops the session's held chunks. */
    fun discardHeld(sessionId: String) {}
}

/**
 * The core's [ReplaySink]: queues each closed batch for upload and starts the uploader. [queue] is a provider because
 * the replay database is opened lazily, only once there is something to store.
 */
internal class ReplayIntake(
    private val isDisabled: () -> Boolean,
    private val queue: () -> ReplayQueue,
    private val onQueued: () -> Unit,
) : ReplaySink {
    override fun accept(batch: NewReplayBatch) {
        if (isDisabled()) return
        val store = queue()
        if (!store.enqueue(batch)) return
        // An opt-out between the check above and the commit may have cleared the queue before this batch landed in it:
        // look again, and clear again. Either the flag was flipped before this read (clear here) or after it (the
        // opt-out's own clear runs after the commit).
        if (isDisabled()) {
            store.clear()
            return
        }
        if (!batch.held) onQueued()
    }

    override fun promote(sessionId: String) {
        if (isDisabled()) return
        val n = queue().promote(sessionId)
        if (n > 0) {
            SdkLog.d("replay buffer promoted: $n chunks")
            onQueued()
        }
    }

    override fun discardHeld(sessionId: String) {
        queue().dropHeld(sessionId)
    }
}
