package id.art.tracker.perf

/** One profiling sample. A null metric was unavailable and is left out of the wire form (never sent as 0). */
internal data class PerfSample(
    val t: Long,
    val cpu: Double?,
    val memMb: Double?,
    val fps: Double?,
    val jank: Int?,
    val frozen: Int?,
    val threads: Int?,
    val battery: Double?,
    val thermal: String?,
    val ramFreeMb: Int? = null,
    val storageFreeMb: Int? = null,
    val net: String? = null,
) {
    fun toMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(13)
        m["t"] = t
        cpu?.let { m["cpu"] = round1(it) }
        memMb?.let { m["mem_mb"] = round1(it) }
        fps?.let { m["fps"] = round1(it) }
        jank?.let { m["jank"] = it }
        frozen?.let { m["frozen"] = it }
        threads?.let { m["threads"] = it }
        battery?.let { m["battery"] = it }
        thermal?.let { m["thermal"] = it }
        ramFreeMb?.let { m["ram_free_mb"] = it }
        storageFreeMb?.let { m["storage_free_mb"] = it }
        net?.let { m["net"] = it }
        return m
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
