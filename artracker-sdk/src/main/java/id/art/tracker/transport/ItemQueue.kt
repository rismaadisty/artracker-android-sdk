package id.art.tracker.transport

internal data class StoredItem(
    val id: String,
    val priority: Int,
    val createdAtMs: Long,
    val payloadJson: String,
) {
    /** UTF-8 byte size of [payloadJson]; this is what counts toward batch and storage limits. */
    val sizeBytes: Int = payloadJson.toByteArray(Charsets.UTF_8).size
}

internal data class Batch(val id: String, val items: List<StoredItem>)

internal interface ItemQueue {
    /**
     * Persists [items]. Returns true when the write committed (an id that is already stored is ignored and still
     * counts as success: the row exists), false when nothing could be stored (database unavailable, locked, full or
     * corrupt). Callers that hold the only copy of an item must keep it when this returns false.
     */
    fun insert(items: List<StoredItem>): Boolean

    /**
     * Atomically claims up to [maxItems] pending items (<= [maxBytes] in total, though the first item is
     * always included), ordered by priority then age. Returns null when nothing is pending.
     */
    fun claimBatch(maxItems: Int = 50, maxBytes: Int = 131_072): Batch?

    /** Deletes the items of a successfully delivered batch. */
    fun complete(batchId: String)

    /** Returns the items of a failed batch to pending and counts the attempt. */
    fun release(batchId: String)

    /** Number of items waiting to be claimed (excludes in-flight batches). */
    fun pendingCount(): Int

    /** Makes items left in-flight by a dead process pending again. */
    fun resetOrphanedBatches()

    /** Deletes every row, including in-flight and priority 0 (used when the user opts out). */
    fun clear()

    /** Drops items created before [cutoffMs], except priority 0 (crash/fatal). */
    fun purgeOlderThan(cutoffMs: Long)
}
