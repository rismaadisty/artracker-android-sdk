package id.art.tracker.core

import id.art.tracker.ItemType
import id.art.tracker.TelemetryItem
import id.art.tracker.privacy.AttributeRedactor

/**
 * An immutable set of global properties as the host set it, ready to be merged into the attributes of an item.
 *
 * The caller thread only ever creates and hands out a snapshot (a shallow copy made in [GlobalProperties.set]); the
 * work happens on the pipeline thread, once per snapshot: each entry is redacted on its own ([vet]), an entry that
 * throws (a value whose `toString()` is hostile) is dropped without costing anything else, and the lot is capped to the
 * attribute budget so it can never push an item's own attributes out of it.
 */
internal class GlobalSnapshot(private val raw: Map<String, Any?>) {
    private val vetted: Map<String, Any?> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { vet(raw) }

    /**
     * [item] with the globals added after its own attributes (the item's key wins, even when its value is null).
     * `PERF` items and an empty snapshot return [item] itself. Pipeline thread only: it reads the item's attributes,
     * which may be computed lazily (`session_start`).
     */
    fun applyTo(item: TelemetryItem): TelemetryItem {
        if (item.type == ItemType.PERF || raw.isEmpty()) return item
        val extra = vetted
        if (extra.isEmpty()) return item
        val merged = LinkedHashMap<String, Any?>(item.attributes)
        for ((key, value) in extra) if (!merged.containsKey(key)) merged[key] = value
        return item.copy(attributes = merged)
    }

    private fun vet(raw: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        var total = 2 // "{}"
        var seen = 0
        try {
            // As Map<*, *>: a raw Java map can hold any key, and reading it as String would throw outside the per-entry try.
            for (entry in (raw as Map<*, *>).entries) {
                seen++
                // Nothing more can fit: stop instead of redacting thousands of entries that would be dropped anyway.
                if (total >= AttributeRedactor.MAX_BYTES - MIN_ENTRY_BYTES) break
                try {
                    val one = AttributeRedactor.redact(mapOf(entry.key.toString() to entry.value))
                    if (one.containsKey(TRUNCATED_KEY)) continue // alone it is bigger than the whole budget
                    for ((k, v) in one) {
                        val size = byteSize(Json.encode(k)) + 1 + byteSize(Json.encode(v)) + 1
                        if (total + size > AttributeRedactor.MAX_BYTES) continue
                        out[k] = v
                        total += size
                    }
                } catch (_: Throwable) {
                    // A hostile value costs this entry only.
                }
            }
            seen = raw.size
        } catch (_: Throwable) {
            // The map itself could not be iterated: what was read so far stays.
        }
        val dropped = seen - out.size
        if (dropped > 0) SdkLog.w("$dropped global propert${if (dropped == 1) "y was" else "ies were"} left out (unreadable or over budget)")
        return out
    }

    private fun byteSize(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    companion object {
        val EMPTY = GlobalSnapshot(emptyMap())
        private const val TRUNCATED_KEY = "_truncated"
        private const val MIN_ENTRY_BYTES = 8
    }
}

/** The properties currently set by the host: one volatile reference to an immutable snapshot, no lock. */
internal class GlobalProperties {
    @Volatile
    private var snapshot: GlobalSnapshot = GlobalSnapshot.EMPTY

    fun current(): GlobalSnapshot = snapshot

    /** Replaces the whole set. A map that cannot be copied throws before anything changes: the old set stays. */
    fun set(properties: Map<String, Any?>) {
        snapshot = if (properties.isEmpty()) GlobalSnapshot.EMPTY else GlobalSnapshot(LinkedHashMap(properties))
    }

    fun clear() {
        snapshot = GlobalSnapshot.EMPTY
    }
}
