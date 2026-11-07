package id.art.tracker.playground.labs

import id.art.tracker.playground.inspector.InspectedItem

object FingerprintCounter {
    /** `exception` items grouped by their `fingerprint` attribute ("?" when it is missing). */
    fun countExceptions(items: List<InspectedItem>): Map<String, Int> =
        items.filter { it.name == "exception" }.groupingBy { it.attributes["fingerprint"] ?: "?" }.eachCount()
}
