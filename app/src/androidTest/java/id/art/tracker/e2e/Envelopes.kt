package id.art.tracker.e2e

import id.art.tracker.MockIngest
import org.json.JSONArray
import org.json.JSONObject

/** Every item of every recorded ingest envelope. */
internal fun List<MockIngest.Recorded>.items(): List<JSONObject> = flatMap { r ->
    val array: JSONArray = runCatching { JSONObject(r.body).optJSONArray("items") }.getOrNull() ?: JSONArray()
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/** The replay uploads, one per recorded `PUT`: the `artracker-screen-v1` documents (`frames`, `touches`). */
internal fun List<MockIngest.Recorded>.chunks(): List<JSONObject> =
    filter { it.method == "PUT" && it.path.substringBefore('?') == MockIngest.UPLOAD_PATH }
        .mapNotNull { r -> runCatching { JSONObject(r.body) }.getOrNull()?.takeIf { it.optString("format") == "artracker-screen-v1" } }

/** (kind, x, y) of every touch in the chunks: kind is `down`, `move` or `up`. x and y are dp. */
internal fun List<JSONObject>.touches(): List<Triple<String, Int, Int>> = flatMap { chunk ->
    val touches = chunk.optJSONArray("touches") ?: JSONArray()
    (0 until touches.length()).mapNotNull { i ->
        val t = touches.optJSONObject(i) ?: return@mapNotNull null
        Triple(t.optString("kind"), t.optInt("x"), t.optInt("y"))
    }
}

internal fun List<MockIngest.Recorded>.hasItemNamed(name: String) = items().any { it.optString("name") == name }
