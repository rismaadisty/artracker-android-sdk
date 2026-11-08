package id.art.tracker.telemetry

import id.art.tracker.core.Clock
import id.art.tracker.core.Iso8601
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Fixed-size ring of the most recent user/app actions (ARCHITECTURE.md section 8.3). They are never sent on
 * their own: only attached to crashes, handled errors and the like.
 *
 * Lock-free on purpose: [add] is called from `ScreenTracker` (inside its monitor), from `track()` on the main
 * thread and so on, and [snapshot] from the crash handler on a dying thread, which must never wait for a lock.
 * Writers claim a slot with an atomic counter; readers order what they find by that counter, so a snapshot
 * taken during concurrent writes may miss an entry that is being written but never returns a torn one.
 *
 * Seam for Task 17: the OkHttp interceptor adds `type = "http"` breadcrumbs through [add]; nothing else is
 * needed here.
 */
internal class Breadcrumbs(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: Clock = Clock.SYSTEM,
) {
    private class Entry(
        val seq: Long,
        val tsMs: Long,
        val type: String,
        val message: String,
        val data: Map<String, Any?>,
    )

    private val slots = AtomicReferenceArray<Entry?>(capacity.coerceAtLeast(1))
    private val counter = AtomicLong(0)

    /** Never throws. [message] and [data] are clipped here, so an entry is always small and immutable. */
    fun add(type: String, message: String, data: Map<String, Any?> = emptyMap()) {
        try {
            val seq = counter.getAndIncrement()
            val entry = Entry(seq, clock.nowMs(), clip(type, MAX_TYPE), clip(message, MAX_MESSAGE), sanitize(data))
            slots.set((seq % slots.length()).toInt(), entry)
        } catch (_: Throwable) {
            // A breadcrumb is never worth a crash.
        }
    }

    /** Oldest first, at most `capacity` entries, each `{ts (ISO-8601), type, message, data}`. */
    fun snapshot(): List<Map<String, Any?>> {
        val found = ArrayList<Entry>(slots.length())
        for (i in 0 until slots.length()) slots.get(i)?.let { found += it }
        found.sortBy { it.seq }
        val out = ArrayList<Map<String, Any?>>(found.size)
        for (e in found) {
            val m = LinkedHashMap<String, Any?>(4)
            m["ts"] = Iso8601.format(e.tsMs)
            m["type"] = e.type
            m["message"] = e.message
            m["data"] = e.data
            out += m
        }
        return out
    }

    private fun sanitize(data: Map<String, Any?>): Map<String, Any?> {
        if (data.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>(minOf(data.size, MAX_DATA_ENTRIES))
        for ((k, v) in data) {
            if (out.size >= MAX_DATA_ENTRIES) break
            out[clip(k, MAX_KEY)] = when (v) {
                null, is Boolean, is Number -> v
                is String -> clip(v, MAX_VALUE)
                else -> clip(v.toString(), MAX_VALUE)
            }
        }
        return out
    }

    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }

    internal companion object {
        const val DEFAULT_CAPACITY = 30
        private const val MAX_TYPE = 32
        private const val MAX_MESSAGE = 200
        private const val MAX_KEY = 64
        private const val MAX_VALUE = 200
        private const val MAX_DATA_ENTRIES = 8
    }
}
