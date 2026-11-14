package id.art.tracker

import id.art.tracker.core.Iso8601
import id.art.tracker.core.Json
import org.json.JSONArray
import org.json.JSONObject

/** Kind of telemetry item sent to the ingest API. */
public enum class ItemType(internal val wire: String) {
    EVENT("event"),
    LOG("log"),
    SESSION("session"),
    PERF("perf"),
}

/** A single telemetry item. */
public data class TelemetryItem(
    val id: String,
    val type: ItemType,
    val name: String,
    val level: LogLevel?,
    val message: String?,
    val timestampMs: Long,
    val sessionId: String,
    val userId: String?,
    val attributes: Map<String, Any?>,
)

/** Hook that can modify or drop (return null) an item before it is queued. */
public fun interface BeforeSend {
    public fun process(item: TelemetryItem): TelemetryItem?
}

internal fun TelemetryItem.toJson(): String {
    val sb = StringBuilder(256)
    sb.append("{\"id\":").append(Json.encode(id))
    sb.append(",\"type\":").append(Json.encode(type.wire))
    sb.append(",\"name\":").append(Json.encode(name))
    if (type == ItemType.LOG && level != null) {
        sb.append(",\"level\":").append(Json.encode(level.wire))
    }
    if (message != null) sb.append(",\"message\":").append(Json.encode(message))
    sb.append(",\"timestamp\":").append(Json.encode(Iso8601.format(timestampMs)))
    sb.append(",\"session_id\":").append(Json.encode(sessionId))
    if (userId != null) sb.append(",\"user_id\":").append(Json.encode(userId))
    sb.append(",\"attributes\":").append(Json.encode(attributes))
    sb.append('}')
    return sb.toString()
}

internal fun TelemetryItem.priority(): Int = when {
    level == LogLevel.FATAL -> 0
    level == LogLevel.ERROR -> 1
    else -> 2
}

/**
 * Reads back the JSON written by [toJson] (and by `CrashItemBuilder`, which writes the same shape by hand). Null when a
 * required field (`id`, `type`, `name`, `timestamp`, `session_id`) is missing or malformed, or `type` / `level` is not
 * one this SDK knows. Numbers keep the type `org.json` gives them (`Int`, `Long` or `Double`), so a large `Long`
 * attribute stays a `Long`.
 */
internal fun telemetryItemFromJson(json: String): TelemetryItem? = try {
    val o = JSONObject(json)
    val type = ItemType.values().firstOrNull { it.wire == o.optString("type") }
    val levelWire = o.optString("level", "")
    val level = if (levelWire.isEmpty()) null else LogLevel.values().firstOrNull { it.wire == levelWire } ?: return null
    val id = o.optString("id", "")
    val name = o.optString("name", "")
    val sessionId = o.optString("session_id", "")
    val timestamp = Iso8601.parse(o.optString("timestamp", ""))
    if (type == null || id.isEmpty() || name.isEmpty() || sessionId.isEmpty() || timestamp == null) {
        null
    } else {
        @Suppress("UNCHECKED_CAST")
        TelemetryItem(
            id = id,
            type = type,
            name = name,
            level = level,
            message = if (o.has("message") && !o.isNull("message")) o.getString("message") else null,
            timestampMs = timestamp,
            sessionId = sessionId,
            userId = if (o.has("user_id") && !o.isNull("user_id")) o.getString("user_id") else null,
            attributes = (o.optJSONObject("attributes")?.let(::jsonToMap) ?: emptyMap()),
        )
    }
} catch (_: Throwable) {
    null
}

private fun jsonToMap(o: JSONObject): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(o.length())
    val keys = o.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out[key] = jsonToValue(o.get(key))
    }
    return out
}

private fun jsonToValue(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> jsonToMap(v)
    is JSONArray -> List(v.length()) { jsonToValue(v.get(it)) }
    else -> v
}
