package id.art.tracker.lifecycle

/** The kind of an app start; [wire] is the value of the `type` attribute of the `app_start` item. */
internal enum class StartType(val wire: String) { COLD("cold"), WARM("warm"), HOT("hot") }

/** One contiguous slice of a start. [ms] is never negative. */
internal data class Phase(val name: String, val ms: Long)

/** Differences only are used; values are ms on any single monotonic clock. */
internal data class StartupTimestamps(val forkMs: Long, val bindApplicationMs: Long?, val applicationOnCreateMs: Long?)

private const val PROCESS_TO_INIT = "process_to_init"

/**
 * Turns an anchor and the boundaries that follow it into contiguous phases. Each boundary is raised to the one before
 * it, so a clock that is not monotonic cannot give a negative phase, and the phases always sum to `last - anchor`.
 * A phase of 0 ms is kept, so the dashboard always gets the same names.
 */
private fun contiguous(names: List<String>, anchor: Long, boundaries: List<Long>): List<Phase> {
    var previous = anchor
    return names.indices.map { i ->
        val end = maxOf(previous, boundaries[i])
        Phase(names[i], end - previous).also { previous = end }
    }
}

internal fun coldPhases(processStartMs: Long, initMs: Long, firstCreateMs: Long, resumedMs: Long, frameMs: Long): List<Phase> =
    contiguous(
        listOf(PROCESS_TO_INIT, "application_on_create", "activity_create", "first_draw"),
        processStartMs,
        listOf(initMs, firstCreateMs, resumedMs, frameMs),
    )

internal fun warmPhases(createMs: Long, resumedMs: Long, frameMs: Long): List<Phase> =
    contiguous(listOf("activity_create", "first_draw"), createMs, listOf(resumedMs, frameMs))

internal fun hotPhases(startMs: Long, resumedMs: Long, frameMs: Long): List<Phase> =
    contiguous(listOf("activity_start", "first_draw"), startMs, listOf(resumedMs, frameMs))

/**
 * API 35+: replaces `process_to_init` by `bind_application`, `content_providers` and `app_on_create_pre_init`, so the
 * phases still sum to the same duration. Returns [phases] unchanged when [ts] lacks a boundary or the three parts do
 * not fit inside `process_to_init`.
 */
internal fun splitProcessToInit(phases: List<Phase>, ts: StartupTimestamps): List<Phase> {
    val index = phases.indexOfFirst { it.name == PROCESS_TO_INIT }
    if (index < 0) return phases
    val bindApplication = ts.bindApplicationMs ?: return phases
    val applicationOnCreate = ts.applicationOnCreateMs ?: return phases
    val total = phases[index].ms
    val bind = bindApplication - ts.forkMs
    val providers = applicationOnCreate - bindApplication
    val pre = total - bind - providers
    if (bind < 0 || providers < 0 || pre < 0) return phases
    return phases.subList(0, index) +
        listOf(Phase("bind_application", bind), Phase("content_providers", providers), Phase("app_on_create_pre_init", pre)) +
        phases.subList(index + 1, phases.size)
}
