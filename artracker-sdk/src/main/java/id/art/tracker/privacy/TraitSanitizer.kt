package id.art.tracker.privacy

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
        for (key in traits.keys.sorted()) {
            if (out.size >= MAX_KEYS) break
            if (key.isEmpty() || key.length > MAX_KEY_LEN) continue
            val value = traits[key]
            when (value) {
                null, is Boolean, is Number -> out[key] = value
                is String -> out[key] = if (value.length > MAX_VALUE_LEN) value.substring(0, MAX_VALUE_LEN) else value
                else -> Unit
            }
        }
        return out
    }
}
