package id.art.tracker.replay

import androidx.annotation.VisibleForTesting
import id.art.tracker.core.Json

/**
 * Groups rrweb events into `replay_chunk` attributes `{seq, start, end, full_snapshot, platform, [oversized],
 * events}` (ARCHITECTURE.md section 6.5).
 *
 * - A chunk is closed (returned by [add]) once it reaches [flushAtBytes], before an event that would push it over
 *   [maxBytes], and before a snapshot sequence (a Meta event or a full snapshot) unless it holds only Meta events,
 *   so a full snapshot always opens a chunk together with its Meta. [drain] closes the open chunk.
 * - Sizes are UTF-8 bytes of exactly what is sent: `Json.encode(chunk, Json.REPLAY_MAX_DEPTH)` (the encoding
 *   `TelemetryItem.toJson` uses for replay chunks). Each event is encoded once, with the depth budget it has
 *   inside the chunk, so the sum is exact rather than an estimate.
 * - A single event that cannot fit in an empty chunk is trimmed: the deepest non-empty `childNodes` lists are
 *   emptied level by level (the document and `html` keep theirs) until it fits; the event and its chunk get
 *   `oversized: true`. An event that still does not fit (nothing left to trim) is dropped and [consumeDropped]
 *   reports it, so the caller can force a full snapshot.
 *
 * Not thread-safe: one caller (the replay processing thread). Memory: only the open chunk's events.
 */
internal class ReplayChunker(
    private val maxBytes: Int = 512 * 1024,
    private val flushAtBytes: Int = 64 * 1024,
) {
    private class Entry(val event: Map<String, Any?>, val bytes: Int, val ts: Long?)

    private val open = ArrayList<Entry>()
    private var openEventBytes = 0
    private var openFull = false
    private var openOversized = false
    private var nextSeq = 0
    private var dropped = false

    /** UTF-8 size of the last chunk returned. */
    @VisibleForTesting
    var lastChunkBytes: Int = 0
        private set

    fun add(event: Map<String, Any?>, isFull: Boolean): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>(2)
        val startsSnapshot = isFull || event["type"] == TYPE_META
        if (startsSnapshot && open.any { it.event["type"] != TYPE_META }) close(out)

        var entry = entryOf(event)
        var oversized = false
        if (!fitsAlone(entry, isFull)) {
            val trimmed = trim(event, isFull)
            if (trimmed == null) {
                dropped = true
                return out
            }
            entry = trimmed
            oversized = true
        }
        if (open.isNotEmpty() && sizeWith(entry, isFull, oversized) > maxBytes) close(out)
        open.add(entry)
        openEventBytes += entry.bytes
        if (isFull) openFull = true
        if (oversized) openOversized = true
        if (currentSize() >= flushAtBytes) close(out)
        return out
    }

    /** Closes and returns the open chunk (empty list when there is none). */
    fun drain(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>(1)
        close(out)
        return out
    }

    /** True once after an event was dropped because it could not be trimmed under [maxBytes]. */
    fun consumeDropped(): Boolean {
        val d = dropped
        dropped = false
        return d
    }

    private fun entryOf(event: Map<String, Any?>): Entry {
        // Inside a chunk an event sits at depth 2 (attributes map -> events list -> event).
        val bytes = utf8Size(Json.encode(event, Json.REPLAY_MAX_DEPTH - EVENT_DEPTH))
        return Entry(event, bytes, (event["timestamp"] as? Number)?.toLong())
    }

    /**
     * Measured with the NEXT chunk's seq: the open chunk may be closed before this event is appended, and a seq
     * crossing a digit boundary (9 -> 10) widens the wrapper by one byte.
     */
    private fun fitsAlone(entry: Entry, isFull: Boolean): Boolean =
        wrapperSize(entry.ts, entry.ts, isFull, oversized = true, seq = nextSeq + 1) + entry.bytes <= maxBytes

    private fun sizeWith(entry: Entry, isFull: Boolean, oversized: Boolean): Int {
        val (start, end) = range(entry.ts)
        val wrapper = wrapperSize(start, end, openFull || isFull, openOversized || oversized)
        return wrapper + openEventBytes + entry.bytes + open.size // one comma per event already open
    }

    private fun currentSize(): Int {
        if (open.isEmpty()) return 0
        val (start, end) = range(null)
        return wrapperSize(start, end, openFull, openOversized) + openEventBytes + open.size - 1
    }

    private fun range(extra: Long?): Pair<Long?, Long?> {
        var start: Long? = extra
        var end: Long? = extra
        for (e in open) {
            val ts = e.ts ?: continue
            if (start == null || ts < start) start = ts
            if (end == null || ts > end) end = ts
        }
        return start to end
    }

    /** Size of the chunk attributes with an empty events list. */
    private fun wrapperSize(start: Long?, end: Long?, full: Boolean, oversized: Boolean, seq: Int = nextSeq): Int =
        utf8Size(Json.encode(attributes(seq, start, end, full, oversized, emptyList()), Json.REPLAY_MAX_DEPTH))

    private fun attributes(
        seq: Int,
        start: Long?,
        end: Long?,
        full: Boolean,
        oversized: Boolean,
        events: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(8)
        m["seq"] = seq
        m["start"] = start
        m["end"] = end
        m["full_snapshot"] = full
        m["platform"] = PLATFORM
        if (oversized) m["oversized"] = true
        m["events"] = events
        return m
    }

    private fun close(out: MutableList<Map<String, Any?>>) {
        if (open.isEmpty()) return
        val size = currentSize()
        val (start, end) = range(null)
        out.add(attributes(nextSeq, start, end, openFull, openOversized, open.map { it.event }))
        lastChunkBytes = size
        nextSeq++
        open.clear()
        openEventBytes = 0
        openFull = false
        openOversized = false
    }

    /** Empties the deepest non-empty `childNodes` level until the event fits alone; null when it never does. */
    private fun trim(event: Map<String, Any?>, isFull: Boolean): Entry? {
        var current: Map<String, Any?> = event + (KEY_OVERSIZED to true)
        while (true) {
            val depth = deepestTrimmable(current, 0)
            if (depth < 0) return null
            @Suppress("UNCHECKED_CAST")
            current = cut(current, 0, depth) as Map<String, Any?>
            val entry = entryOf(current)
            if (fitsAlone(entry, isFull)) return entry
        }
    }

    private fun isProtected(node: Map<*, *>): Boolean = node["type"] == NODE_DOCUMENT || node["tagName"] == "html"

    /** Depth of the deepest map holding a non-empty, trimmable `childNodes` list, or -1. */
    private fun deepestTrimmable(value: Any?, depth: Int): Int = when (value) {
        is Map<*, *> -> {
            var best = -1
            val kids = value[KEY_CHILD_NODES]
            if (kids is List<*> && kids.isNotEmpty() && !isProtected(value)) best = depth
            for (v in value.values) best = maxOf(best, deepestTrimmable(v, depth + 1))
            best
        }
        is List<*> -> value.fold(-1) { acc, v -> maxOf(acc, deepestTrimmable(v, depth + 1)) }
        else -> -1
    }

    /** Copy of [value] with the `childNodes` of trimmable maps at [target] depth emptied. */
    private fun cut(value: Any?, depth: Int, target: Int): Any? = when {
        depth > target -> value
        value is Map<*, *> -> {
            val copy = LinkedHashMap<String, Any?>(value.size)
            val trimHere = depth == target && !isProtected(value) && (value[KEY_CHILD_NODES] as? List<*>)?.isNotEmpty() == true
            for ((k, v) in value) {
                copy[k.toString()] = if (trimHere && k == KEY_CHILD_NODES) emptyList<Any?>() else cut(v, depth + 1, target)
            }
            copy
        }
        value is List<*> -> value.map { cut(it, depth + 1, target) }
        else -> value
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
        const val PLATFORM = "android"
        const val TYPE_META = 4
        const val NODE_DOCUMENT = 0
        const val EVENT_DEPTH = 2
        const val KEY_CHILD_NODES = "childNodes"
        const val KEY_OVERSIZED = "oversized"
    }
}
