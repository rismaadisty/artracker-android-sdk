package id.art.tracker.lifecycle

/** Device facts of an `app_start` item; a null field is left out of the item. */
internal data class AppStartDevice(val model: String?, val osVersion: String?, val ramMb: Int?, val cores: Int?)

/**
 * The attributes of a `perf` item named `app_start` (wire contract v1). A value that is not known is omitted, never 0:
 * no `build` key without a build, no `device` key without any device fact, and no `ram_mb` without a RAM size.
 */
internal fun appStartAttributes(
    start: AppStart,
    startId: String,
    profiled: Boolean,
    build: String?,
    device: AppStartDevice,
): Map<String, Any?> {
    val attrs = LinkedHashMap<String, Any?>(12)
    attrs["v"] = 1
    attrs["platform"] = "android"
    attrs["start_id"] = startId
    attrs["type"] = start.type.wire
    attrs["duration_ms"] = start.durationMs
    attrs["phases"] = start.phases.map { linkedMapOf<String, Any?>("name" to it.name, "ms" to it.ms) }
    attrs["first_screen"] = start.firstScreen
    attrs["trampoline"] = start.trampoline
    attrs["profiled"] = profiled
    build?.let { attrs["build"] = it }
    val deviceAttrs = LinkedHashMap<String, Any?>(4)
    device.model?.let { deviceAttrs["model"] = it }
    device.osVersion?.let { deviceAttrs["os_version"] = it }
    device.ramMb?.let { deviceAttrs["ram_mb"] = it }
    device.cores?.let { deviceAttrs["cores"] = it }
    if (deviceAttrs.isNotEmpty()) attrs["device"] = deviceAttrs
    return attrs
}
