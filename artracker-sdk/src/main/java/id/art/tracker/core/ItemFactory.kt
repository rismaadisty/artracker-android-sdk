package id.art.tracker.core

import id.art.tracker.ItemType
import id.art.tracker.LogLevel
import id.art.tracker.TelemetryItem

internal class ItemFactory(
    private val clock: Clock,
    private val sessionId: () -> String,
    private val userId: () -> String?,
) {
    fun event(name: String, attrs: Map<String, Any?> = emptyMap()): TelemetryItem =
        build(ItemType.EVENT, name, null, null, attrs)

    fun log(
        level: LogLevel,
        name: String,
        message: String?,
        attrs: Map<String, Any?> = emptyMap(),
    ): TelemetryItem = build(ItemType.LOG, name, level, message, attrs)

    fun session(name: String, attrs: Map<String, Any?> = emptyMap()): TelemetryItem =
        build(ItemType.SESSION, name, null, null, attrs)

    private fun build(
        type: ItemType,
        name: String,
        level: LogLevel?,
        message: String?,
        attrs: Map<String, Any?>,
    ): TelemetryItem {
        val now = clock.nowMs()
        return TelemetryItem(
            id = UuidV7.generate(now),
            type = type,
            name = name.limit(MAX_NAME),
            level = level,
            message = message?.limit(MAX_MESSAGE),
            timestampMs = now,
            sessionId = sessionId().limit(MAX_ID),
            userId = userId()?.limit(MAX_ID),
            attributes = attrs,
        )
    }

    private companion object {
        const val MAX_ID = 128
    }
}

internal const val MAX_NAME: Int = 255
internal const val MAX_MESSAGE: Int = 4096

/** Cuts to at most [max] chars without leaving a dangling high surrogate at the cut. */
internal fun String.limit(max: Int): String {
    if (length <= max) return this
    val end = if (Character.isHighSurrogate(this[max - 1])) max - 1 else max
    return substring(0, end)
}
