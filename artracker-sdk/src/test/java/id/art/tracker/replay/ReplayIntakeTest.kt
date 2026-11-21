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
        var promoteResult = 0
        val promoted = mutableListOf<String>()
        val droppedHeld = mutableListOf<String>()
        override fun promote(sessionId: String): Int {
            promoted += sessionId
            return promoteResult
        }
        override fun promoteHeldBefore(cutoffMs: Long): Int = 0
        override fun dropHeld(sessionId: String): Int {
            droppedHeld += sessionId
            return 0
        }
        override fun dropHeldBefore(cutoffMs: Long): Int = 0
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

    @Test
    fun heldBatch_isStored_butDoesNotKick() {
        val queue = RecordingQueue()
        var kicks = 0
        ReplayIntake({ false }, { queue }, { kicks++ }).accept(NewReplayBatch("session-xyz-1", byteArrayOf(1), 1, 1, 2, false, null, "test", held = true))

        assertEquals(1, queue.enqueued)
        assertEquals(0, kicks)
    }

    @Test
    fun promote_kicksOnlyWhenRowsPromoted() {
        val queue = RecordingQueue()
        var kicks = 0
        val intake = ReplayIntake({ false }, { queue }, { kicks++ })

        queue.promoteResult = 0
        intake.promote("session-xyz-1")
        assertEquals(0, kicks)

        queue.promoteResult = 2
        intake.promote("session-xyz-1")
        assertEquals(1, kicks)
        assertEquals(listOf("session-xyz-1", "session-xyz-1"), queue.promoted)
    }

    @Test
    fun promote_whileDisabled_doesNothing() {
        val queue = RecordingQueue().also { it.promoteResult = 2 }
        var kicks = 0
        ReplayIntake({ true }, { queue }, { kicks++ }).promote("session-xyz-1")

        assertTrue(queue.promoted.isEmpty())
        assertEquals(0, kicks)
    }

    @Test
    fun discardHeld_dropsSessionHeldRows() {
        val queue = RecordingQueue()
        ReplayIntake({ false }, { queue }, {}).discardHeld("session-xyz-1")

        assertEquals(listOf("session-xyz-1"), queue.droppedHeld)
    }
}
