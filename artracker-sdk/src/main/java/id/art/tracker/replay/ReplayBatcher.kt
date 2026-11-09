package id.art.tracker.replay

import id.art.tracker.core.Clock
import id.art.tracker.core.Json
import id.art.tracker.core.SdkLog
import id.art.tracker.transport.NewReplayBatch
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Receives each closed replay batch (the core queues it for upload). */
internal fun interface ReplaySink {
    fun accept(batch: NewReplayBatch)
}

/**
 * Merges the chunks [ReplayChunker] produces into upload batches: one open batch per session, closed once its raw JSON
 * reaches [maxBytes] (a chunk that would pass the limit starts a new batch instead), [windowMs] after its first
 * event, on a session change, or on [close]. A closed batch is the gzip of a JSON array of rrweb events, the body of
 * the replay upload `PUT`. Only a chunk's `events` are used; its `seq`/`start`/`end` belong to the old ingest path.
 *
 * Not thread-safe: one caller (the replay processing context), like [ReplayChunker]. Memory: the open batch's events.
 */
internal class ReplayBatcher(
    private val sink: ReplaySink,
    private val clock: Clock,
    private val environment: String,
    private val userId: () -> String?,
    private val maxBytes: Int = 256 * 1024,
    private val windowMs: Long = 10_000,
) {
    private val events = ArrayList<Map<String, Any?>>()
    private var sessionId: String? = null
    private var openBytes = 0
    private var openedAtMs = 0L
    private var firstTs = Long.MAX_VALUE
    private var lastTs = Long.MIN_VALUE
    private var hasError = false

    /** [markError] arrived with no batch open: it flags the next one. */
    private var errorPending = false

    fun add(sessionId: String, chunk: Map<String, Any?>) {
        val chunkEvents = (chunk["events"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
        if (chunkEvents.isEmpty()) return
        if (this.sessionId != null && this.sessionId != sessionId) close()

        val size = utf8Size(Json.encode(chunkEvents, Json.REPLAY_MAX_DEPTH))
        if (events.isNotEmpty() && openBytes + size > maxBytes) close()

        if (events.isEmpty()) {
            this.sessionId = sessionId
            openedAtMs = clock.nowMs()
            hasError = errorPending
            errorPending = false
        }
        events.addAll(chunkEvents)
        openBytes += size
        trackTimestamps(chunk, chunkEvents)
        if (openBytes >= maxBytes) close()
    }

    /** The error flag sticks to the open batch, or to the next one when none is open. */
    fun markError() {
        if (events.isNotEmpty()) hasError = true else errorPending = true
    }

    fun closeIfDue() {
        if (events.isNotEmpty() && clock.nowMs() - openedAtMs >= windowMs) close()
    }

    /** Closes the open batch now; a no-op when there is none. */
    fun close() {
        val session = sessionId
        if (events.isEmpty() || session == null) return
        val snapshot = ArrayList(events)
        val first = firstTs
        val last = lastTs
        val flagged = hasError
        reset()

        val body = try {
            gzip(Json.encode(snapshot, Json.REPLAY_MAX_DEPTH))
        } catch (e: Exception) {
            SdkLog.w("encoding replay batch failed (${e.javaClass.simpleName}); dropping ${snapshot.size} events")
            return
        }
        if (body.size > MAX_UPLOAD_BYTES) {
            SdkLog.w("replay batch of ${snapshot.size} events is ${body.size} bytes gzipped; dropped (limit $MAX_UPLOAD_BYTES)")
            return
        }
        val batch = NewReplayBatch(
            sessionId = session,
            gzipBody = body,
            eventCount = snapshot.size,
            firstTsMs = first,
            lastTsMs = last,
            hasError = flagged,
            userId = userId(),
            environment = environment,
        )
        try {
            sink.accept(batch)
        } catch (e: Exception) {
            SdkLog.w("replay sink failed (${e.javaClass.simpleName})")
        }
    }

    /** Drops the open batch (and a pending error flag) without handing anything to the sink. */
    fun discard() {
        reset()
        errorPending = false
    }

    private fun reset() {
        events.clear()
        sessionId = null
        openBytes = 0
        firstTs = Long.MAX_VALUE
        lastTs = Long.MIN_VALUE
        hasError = false
    }

    /** Event timestamps first, then the chunk's own range, then now: a batch always has a usable range. */
    private fun trackTimestamps(chunk: Map<String, Any?>, chunkEvents: List<Map<String, Any?>>) {
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (e in chunkEvents) {
            val ts = (e["timestamp"] as? Number)?.toLong() ?: continue
            if (ts < min) min = ts
            if (ts > max) max = ts
        }
        if (min == Long.MAX_VALUE) {
            val start = (chunk["start"] as? Number)?.toLong()
            val end = (chunk["end"] as? Number)?.toLong()
            when {
                start != null || end != null -> {
                    min = start ?: end!!
                    max = end ?: start!!
                }
                else -> {
                    val now = clock.nowMs()
                    min = now
                    max = now
                }
            }
        }
        if (min < firstTs) firstTs = min
        if (max > lastTs) lastTs = max
    }

    private fun gzip(json: String): ByteArray {
        val out = ByteArrayOutputStream(json.length / 4 + 64)
        GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun utf8Size(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.code < 0x80 -> n += 1
                c.code < 0x800 -> n += 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    n += 4
                    i++
                }
                else -> n += 3
            }
            i++
        }
        return n
    }

    private companion object {
        /** The backend rejects a replay upload above 5 MiB (`size_bytes`). */
        const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
    }
}
