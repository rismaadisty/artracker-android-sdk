package id.art.tracker

import id.art.tracker.core.Iso8601
import id.art.tracker.core.Json

/** Kind of telemetry item sent to the ingest API. */
public enum class ItemType(internal val wire: String) {
    EVENT("event"),
    LOG("log"),
    SESSION("session"),
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
    name == "replay_chunk" -> 3
    else -> 2
}
