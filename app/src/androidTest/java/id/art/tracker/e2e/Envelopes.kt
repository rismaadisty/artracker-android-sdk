package id.art.tracker.e2e

import id.art.tracker.MockIngest
import org.json.JSONArray
import org.json.JSONObject

/** Every item of every recorded ingest envelope. */
internal fun List<MockIngest.Recorded>.items(): List<JSONObject> = flatMap { r ->
    val array: JSONArray = runCatching { JSONObject(r.body).optJSONArray("items") }.getOrNull() ?: JSONArray()
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/**
 * The replay uploads, one per recorded `PUT`, shaped like the old chunk items (`attributes.events`) so [touches] and the
 * assertions that count chunks keep working: the SDK now uploads replay to the Session Replay API instead of `/ingest`.
 */
internal fun List<MockIngest.Recorded>.chunks(): List<JSONObject> =
    filter { it.method == "PUT" && it.path.substringBefore('?') == MockIngest.UPLOAD_PATH }
        .mapNotNull { r ->
            val events = runCatching { JSONArray(r.body) }.getOrNull() ?: return@mapNotNull null
            JSONObject()
                .put("type", "session")
                .put("name", "replay_chunk")
                .put("attributes", JSONObject().put("events", events))
        }

/** (kind, x, y) of every rrweb touch event in the chunks: kind 7 = touchstart, 9 = touchend. x and y are dp. */
internal fun List<JSONObject>.touches(): List<Triple<Int, Int, Int>> = flatMap { chunk ->
    val events = chunk.optJSONObject("attributes")?.optJSONArray("events") ?: JSONArray()
    (0 until events.length()).mapNotNull { i ->
        val e = events.optJSONObject(i) ?: return@mapNotNull null
        val d = e.optJSONObject("data") ?: return@mapNotNull null
        if (e.optInt("type") == 3 && d.optInt("source") == 2 && d.optInt("type") in listOf(7, 9)) {
            Triple(d.optInt("type"), d.optInt("x"), d.optInt("y"))
        } else {
            null
        }
    }
}

internal fun List<MockIngest.Recorded>.hasItemNamed(name: String) = items().any { it.optString("name") == name }
