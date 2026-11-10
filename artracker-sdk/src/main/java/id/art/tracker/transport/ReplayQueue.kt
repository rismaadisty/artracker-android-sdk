package id.art.tracker.transport

/** Body formats of a replay batch; the chunk-url request names it so the server knows how to read the object. */
internal object ReplayFormat {
    /** A gzipped JSON array of rrweb events (wireframe replay). */
    const val RRWEB: String = "rrweb"

    /** A gzipped `artracker-screen-v1` JSON document: screenshot frames and touches. */
    const val SCREEN_V1: String = "artracker-screen-v1"
}

/** One closed replay batch, ready to be queued for upload. [gzipBody] is the gzip of a JSON array of rrweb events. */
internal class NewReplayBatch(
    val sessionId: String,
    val gzipBody: ByteArray,
    val eventCount: Int,
    val firstTsMs: Long,
    val lastTsMs: Long,
    val hasError: Boolean,
    val userId: String?,
    val environment: String,
    val format: String = ReplayFormat.RRWEB,
)

/**
 * A queued batch handed out by [ReplayQueue.claim]. [seq] is assigned by the queue (persisted per session),
 * [startMs] is the first timestamp of the session's first batch and [durationMs] runs from there to this batch's last event.
 */
internal class ReplayUpload(
    val id: String,
    val sessionId: String,
    val seq: Int,
    val gzipBody: ByteArray,
    val eventCount: Int,
    val startMs: Long,
    val durationMs: Long,
    val hasError: Boolean,
    val userId: String?,
    val environment: String,
    val format: String = ReplayFormat.RRWEB,
)

/**
 * Persistent queue of replay batches, separate from the ingest queue. Hot-path methods never throw: on a storage
 * failure they log and degrade (enqueue returns false, claim returns null).
 */
internal interface ReplayQueue {
    /** Stores [batch] and assigns its `seq` inside the same transaction. False when it could not be stored. */
    fun enqueue(batch: NewReplayBatch): Boolean

    /** Oldest pending batch, marked in-flight; null when nothing is pending. */
    fun claim(): ReplayUpload?

    /** The batch was delivered or permanently rejected: delete it. */
    fun complete(id: String)

    /** The batch was not delivered: make it pending again with `attempts + 1`. */
    fun release(id: String)

    /** Batches that [claim] could hand out right now. */
    fun pendingCount(): Int

    /** Makes every in-flight batch pending again (a process that died mid-upload). */
    fun resetOrphaned()

    /** Deletes every batch but keeps the per-session `seq` counters. */
    fun clear()

    /** Deletes batches and `seq` counters last touched before [cutoffMs]. */
    fun purgeOlderThan(cutoffMs: Long)
}
