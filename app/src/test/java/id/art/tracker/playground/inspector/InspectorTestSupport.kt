package id.art.tracker.playground.inspector

internal fun inspected(
    name: String,
    type: String = "event",
    message: String? = null,
    attributes: Map<String, String> = emptyMap(),
    source: ItemSource = ItemSource.SENT_PAYLOAD,
) = InspectedItem(
    id = "id-$name", type = type, name = name, level = null, message = message, timestampMs = 0L,
    sessionId = "s", userId = null, attributes = attributes, truncated = false, source = source,
)

/** One serialised item of an ingest envelope (what `TelemetryItem.toJson` produces). */
internal fun itemJson(
    name: String,
    type: String = "event",
    attributes: String = "{}",
    level: String? = null,
    message: String? = null,
    userId: String? = null,
    session: String = "session-1",
): String = buildString {
    append("{\"id\":\"i-").append(name).append("\",\"type\":\"").append(type).append("\",\"name\":\"").append(name).append('"')
    if (level != null) append(",\"level\":\"").append(level).append('"')
    if (message != null) append(",\"message\":\"").append(message).append('"')
    append(",\"timestamp\":\"2026-11-07T10:00:00.000Z\",\"session_id\":\"").append(session).append('"')
    if (userId != null) append(",\"user_id\":\"").append(userId).append('"')
    append(",\"attributes\":").append(attributes).append('}')
}

internal fun envelope(vararg items: String): String =
    "{\"sent_at\":\"2026-11-07T10:00:01.000Z\",\"sdk\":{\"name\":\"artracker-android\",\"version\":\"0.1.0\"}," +
        "\"context\":{\"environment\":\"sample\"},\"items\":[" + items.joinToString(",") + "]}"
