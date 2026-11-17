package id.art.tracker.privacy

import id.art.tracker.core.limit

/**
 * Bounds the traits of `identify()` to the People wire contract, before the pipeline's redaction:
 * at most [MAX_KEYS] keys (the first ones in sorted key order), keys of 1..[MAX_KEY_LEN] chars, `String` values
 * truncated to [MAX_VALUE_LEN] chars, and only `String`, `Number`, `Boolean` or `null` values. Anything else
 * (collections, maps, arrays, dates, enums, other objects) is dropped, never `toString()`-ed.
 */
internal object TraitSanitizer {
    const val MAX_KEYS = 50
    const val MAX_KEY_LEN = 64
    const val MAX_VALUE_LEN = 256

    /** A new [LinkedHashMap] in sorted-key order; the input is never modified. */
    fun sanitize(traits: Map<String, Any?>): Map<String, Any?> {
        if (traits.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        // A Java caller can pass a map with null or non-String keys: those entries are dropped, nothing throws.
        val keys = (traits.keys as Set<*>).filterIsInstance<String>().sorted()
        for (key in keys) {
            if (out.size >= MAX_KEYS) break
            if (key.isEmpty() || key.length > MAX_KEY_LEN) continue
            val value = traits[key]
            when (value) {
                null, is Boolean, is Number -> out[key] = value
                is String -> out[key] = value.limit(MAX_VALUE_LEN) // never splits a surrogate pair
                else -> Unit
            }
        }
        return out
    }
}
