package id.art.tracker.e2e

import id.art.tracker.MockIngest
import org.json.JSONArray
import org.json.JSONObject

/** Every item of every recorded ingest envelope. */
internal fun List<MockIngest.Recorded>.items(): List<JSONObject> = flatMap { r ->
    val array: JSONArray = runCatching { JSONObject(r.body).optJSONArray("items") }.getOrNull() ?: JSONArray()
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

internal fun JSONObject.isChunk() = optString("type") == "session" && optString("name") == "replay_chunk"

internal fun List<MockIngest.Recorded>.chunks(): List<JSONObject> = items().filter { it.isChunk() }

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
