package id.art.tracker.replay

import id.art.tracker.transport.NewReplayBatch
import id.art.tracker.transport.ReplayQueue
import id.art.tracker.transport.ReplayUpload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayIntakeTest {
    private val batch = NewReplayBatch("session-xyz-1", byteArrayOf(1), 1, 1, 2, false, null, "test")

    /** Records the calls; [onEnqueue] runs inside `enqueue`, where a concurrent opt-out can land. */
    private class RecordingQueue(var onEnqueue: () -> Unit = {}) : ReplayQueue {
        var enqueued = 0
        var cleared = 0

        override fun enqueue(batch: NewReplayBatch): Boolean {
            onEnqueue()
            enqueued++
            return true
        }

        override fun clear() {
            cleared++
        }

        override fun claim(): ReplayUpload? = null
        override fun complete(id: String) = Unit
        override fun release(id: String) = Unit
        override fun pendingCount(): Int = 0
        override fun resetOrphaned() = Unit
        override fun purgeOlderThan(cutoffMs: Long) = Unit
        override fun clearFormat(format: String) = Unit
    }

    @Test
    fun enabled_queuesTheBatchAndStartsTheUpload() {
        val queue = RecordingQueue()
        var kicks = 0
        ReplayIntake({ false }, { queue }, { kicks++ }).accept(batch)

        assertEquals(1, queue.enqueued)
        assertEquals(1, kicks)
        assertEquals(0, queue.cleared)
    }

    @Test
    fun disabled_dropsTheBatchWithoutOpeningTheQueue() {
        var opened = false
        var kicks = 0
        ReplayIntake({ true }, { opened = true; RecordingQueue() }, { kicks++ }).accept(batch)

        assertTrue(!opened)
        assertEquals(0, kicks)
    }

    @Test
    fun optOutDuringTheEnqueue_clearsWhatWasJustStored_andDoesNotUpload() {
        var disabled = false
        val queue = RecordingQueue(onEnqueue = { disabled = true }) // the opt-out lands while the batch is being stored
        var kicks = 0

        ReplayIntake({ disabled }, { queue }, { kicks++ }).accept(batch)

        // The opt-out's own clear() may already have run before this batch was committed: it must be cleared again.
        assertEquals(1, queue.cleared)
        assertEquals(0, kicks)
    }
}
