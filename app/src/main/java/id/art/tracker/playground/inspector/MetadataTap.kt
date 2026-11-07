package id.art.tracker.playground.inspector

import id.art.tracker.BeforeSend
import id.art.tracker.TelemetryItem

/**
 * Value-free description of an item, taken in `beforeSend` (which runs BEFORE the SDK redacts anything). It stores the
 * type, level, timestamp, an 8-char session prefix, a name and attribute keys that pass an allow-list, and only the
 * *kind* of each value. Messages, user ids and values are never read into the result.
 */
object MetadataTap {
    private val SAFE = Regex("^[A-Za-z0-9_.$:/-]{1,64}$")
    private const val MASKED_NAME = "<nama disamarkan>"
    private const val MASKED_KEY = "<kunci disamarkan>"

    val beforeSend = BeforeSend { item ->
        runCatching { Inspector.ring.add(toInspected(item)) }
        item
    }

    fun toInspected(item: TelemetryItem): InspectedItem = runCatching {
        val attrs = LinkedHashMap<String, String>()
        var maskedKeys = 0
        for ((key, value) in item.attributes) {
            val shownKey = when {
                SAFE.matches(key) -> key
                ++maskedKeys == 1 -> MASKED_KEY
                else -> "$MASKED_KEY #$maskedKeys"
            }
            attrs[shownKey] = kindOf(value)
        }
        InspectedItem(
            id = item.id,
            type = item.type.name.lowercase(),
            name = if (SAFE.matches(item.name)) item.name else MASKED_NAME,
            level = item.level?.name?.lowercase(),
            message = null,
            timestampMs = item.timestampMs,
            sessionId = item.sessionId.take(8),
            userId = null,
            attributes = attrs,
            truncated = false,
            source = ItemSource.METADATA_ONLY,
        )
    }.getOrElse {
        InspectedItem("?", "?", MASKED_NAME, null, null, 0L, "", null, emptyMap(), false, ItemSource.METADATA_ONLY)
    }

    private fun kindOf(value: Any?): String = when (value) {
        null -> "null"
        is String -> "string(${value.length})"
        is Boolean -> "boolean"
        is Number -> "number"
        is Map<*, *> -> "map"
        is Iterable<*>, is Array<*> -> "list"
        else -> "object"
    }
}
