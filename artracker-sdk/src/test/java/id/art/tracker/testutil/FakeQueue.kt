package id.art.tracker.testutil

import id.art.tracker.transport.Batch
import id.art.tracker.transport.ItemQueue
import id.art.tracker.transport.StoredItem

/** In-memory [ItemQueue] mirroring EventStore semantics: priority/age ordering, claim, release, complete. */
internal class FakeQueue : ItemQueue {
    private val lock = Any()
    private val pending = ArrayList<StoredItem>()
    private val inFlight = LinkedHashMap<String, List<StoredItem>>()
    private var nextBatch = 0

    /** Number of times each batch was released (test visibility). */
    val releasedBatches = ArrayList<String>()
    val completedBatches = ArrayList<String>()

    override fun insert(items: List<StoredItem>) = synchronized(lock) {
        for (item in items) {
            if (pending.none { it.id == item.id } && inFlight.values.none { b -> b.any { it.id == item.id } }) {
                pending += item
            }
        }
        Unit
    }

    override fun claimBatch(maxItems: Int, maxBytes: Int): Batch? = synchronized(lock) {
        val sorted = pending.sortedWith(compareBy({ it.priority }, { it.createdAtMs }, { it.id }))
        val claimed = ArrayList<StoredItem>()
        var total = 0L
        for (item in sorted) {
            if (claimed.size >= maxItems.coerceAtLeast(1)) break
            if (claimed.isNotEmpty() && total + item.sizeBytes > maxBytes) break
            total += item.sizeBytes
            claimed += item
        }
        if (claimed.isEmpty()) return null
        pending.removeAll(claimed.toSet())
        val id = "batch-${nextBatch++}"
        inFlight[id] = claimed
        Batch(id, claimed)
    }

    override fun complete(batchId: String) = synchronized(lock) {
        inFlight.remove(batchId)
        completedBatches += batchId
        Unit
    }

    override fun release(batchId: String) = synchronized(lock) {
        inFlight.remove(batchId)?.let { pending += it }
        releasedBatches += batchId
        Unit
    }

    override fun pendingCount(): Int = synchronized(lock) { pending.size }

    override fun resetOrphanedBatches() = synchronized(lock) {
        inFlight.values.forEach { pending += it }
        inFlight.clear()
    }

    override fun purgeOlderThan(cutoffMs: Long) = synchronized(lock) {
        pending.removeAll { it.createdAtMs < cutoffMs && it.priority > 0 }
        Unit
    }

    fun inFlightCount(): Int = synchronized(lock) { inFlight.values.sumOf { it.size } }
}
