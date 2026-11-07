package id.art.tracker.playground.labs

enum class PropertyType { STRING, NUMBER, BOOLEAN, NULL }

data class PropertyRow(val key: String, val type: PropertyType, val raw: String)

sealed interface BuildResult {
    data class Ok(val map: Map<String, Any?>) : BuildResult
    data class Error(val row: Int, val message: String) : BuildResult
}

/** Turns the Event lab's property rows into the map handed to `ARTracker.track`. */
object PropertyBuilder {
    fun build(rows: List<PropertyRow>): BuildResult {
        val map = LinkedHashMap<String, Any?>()
        rows.forEachIndexed { index, row ->
            val key = row.key.trim()
            if (key.isEmpty()) return BuildResult.Error(index, "kunci kosong")
            if (map.containsKey(key)) return BuildResult.Error(index, "kunci ganda: $key")
            map[key] = when (row.type) {
                PropertyType.STRING -> row.raw
                PropertyType.NUMBER -> row.raw.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull() }
                    ?: return BuildResult.Error(index, "angka tidak valid")
                PropertyType.BOOLEAN -> when (row.raw.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> return BuildResult.Error(index, "boolean harus true atau false")
                }
                PropertyType.NULL -> null
            }
        }
        return BuildResult.Ok(map)
    }
}
