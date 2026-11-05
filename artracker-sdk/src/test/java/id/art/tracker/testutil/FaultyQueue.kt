package id.art.tracker.testutil

import id.art.tracker.transport.Batch
import id.art.tracker.transport.ItemQueue

/** Decorates [delegate]; the configured operations throw before touching it. */
internal class FaultyQueue(
    val delegate: FakeQueue,
    var failComplete: Throwable? = null,
    var failRelease: Throwable? = null,
    var failClear: Throwable? = null,
) : ItemQueue by delegate {
    var completeCalls = 0
    var releaseCalls = 0

    override fun complete(batchId: String) {
        completeCalls++
        failComplete?.let { throw it }
        delegate.complete(batchId)
    }

    override fun release(batchId: String) {
        releaseCalls++
        failRelease?.let { throw it }
        delegate.release(batchId)
    }

    override fun clear() {
        failClear?.let { throw it }
        delegate.clear()
    }

    override fun claimBatch(maxItems: Int, maxBytes: Int): Batch? = delegate.claimBatch(maxItems, maxBytes)
}
