package id.art.tracker.playground.inspector

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** Reads an ingest envelope (`{sent_at, sdk, context, items:[...]}`) into [InspectedItem]s. Never throws. */
object EnvelopeParser {
    private const val MAX_ITEMS = 500
    private const val MAX_VALUE = 200
    private val CHUNK_KEYS = listOf("seq", "start", "end", "full_snapshot", "platform")

    private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun parse(gunzippedJson: String): List<InspectedItem> = try {
        val items = JSONObject(gunzippedJson).optJSONArray("items")
        val out = ArrayList<InspectedItem>()
        if (items != null) {
            for (i in 0 until minOf(items.length(), MAX_ITEMS)) {
                val obj = items.optJSONObject(i) ?: continue
                runCatching { toItem(obj) }.getOrNull()?.let(out::add)
            }
        }
        out
    } catch (_: Throwable) {
        emptyList()
    }

    /** The `attributes` object of every `replay_chunk` item, as JSON text, intact (for the debug export). */
    fun replayChunkAttributes(gunzippedJson: String): List<String> = try {
        val items = JSONObject(gunzippedJson).optJSONArray("items")
        val out = ArrayList<String>()
        if (items != null) {
            for (i in 0 until minOf(items.length(), MAX_ITEMS)) {
                val obj = items.optJSONObject(i) ?: continue
                if (isReplayChunk(obj)) obj.optJSONObject("attributes")?.let { out += it.toString() }
            }
        }
        out
    } catch (_: Throwable) {
        emptyList()
    }

    private fun isReplayChunk(obj: JSONObject) = obj.optString("type") == "session" && obj.optString("name") == "replay_chunk"

    private fun toItem(obj: JSONObject): InspectedItem {
        val attrs = LinkedHashMap<String, String>()
        var truncated = false
        val source = obj.optJSONObject("attributes")
        if (source != null) {
            if (isReplayChunk(obj)) {
                for (key in CHUNK_KEYS) if (source.has(key)) attrs[key] = source.opt(key).toString().take(MAX_VALUE)
                attrs["events"] = "${source.optJSONArray("events")?.length() ?: 0} events"
                truncated = true
            } else {
                val keys = source.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val raw = source.opt(key)
                    val text = if (raw == null || raw == JSONObject.NULL) "null" else raw.toString()
                    if (text.length > MAX_VALUE) truncated = true
                    attrs[key] = text.take(MAX_VALUE)
                }
            }
        }
        return InspectedItem(
            id = obj.optString("id"),
            type = obj.optString("type"),
            name = obj.optString("name"),
            level = if (obj.has("level")) obj.optString("level") else null,
            message = if (obj.has("message")) obj.optString("message") else null,
            timestampMs = runCatching { timestampFormat.get()!!.parse(obj.optString("timestamp"))!!.time }.getOrDefault(0L),
            sessionId = obj.optString("session_id"),
            userId = if (obj.has("user_id")) obj.optString("user_id") else null,
            attributes = attrs,
            truncated = truncated,
            source = ItemSource.SENT_PAYLOAD,
        )
    }
}
