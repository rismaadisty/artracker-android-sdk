package id.art.tracker.playground.inspector

data class InspectorFilter(val types: Set<String> = emptySet(), val query: String = "") {
    fun matches(item: InspectedItem): Boolean {
        if (types.isNotEmpty() && item.type !in types) return false
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return item.name.lowercase().contains(q) ||
            item.message?.lowercase()?.contains(q) == true ||
            item.attributes.any { (k, v) -> k.lowercase().contains(q) || v.lowercase().contains(q) }
    }
}
