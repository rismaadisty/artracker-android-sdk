package id.art.tracker.perf

/** The fields of `/proc/self/stat` the profiler needs: CPU time in clock ticks and the thread count. */
internal data class ProcStat(val utimeTicks: Long, val stimeTicks: Long, val threads: Int)

private const val IDX_UTIME = 11
private const val IDX_STIME = 12
private const val IDX_THREADS = 17

/**
 * Parses one `/proc/<pid>/stat` line. Field 2 is `(comm)` and comm may itself contain spaces and parentheses, so
 * everything is read after the LAST `)`. Returns null for anything that does not look like a stat line.
 */
internal fun parseProcStat(line: String): ProcStat? {
    val close = line.lastIndexOf(')')
    if (close < 0 || close + 2 > line.length) return null
    val fields = line.substring(close + 2).trim().split(' ')
    if (fields.size <= IDX_THREADS) return null
    return runCatching {
        ProcStat(fields[IDX_UTIME].toLong(), fields[IDX_STIME].toLong(), fields[IDX_THREADS].toInt())
    }.getOrNull()
}

/** The resident set size in pages: the 2nd whitespace-separated field of `/proc/<pid>/statm`. */
internal fun parseStatmResidentPages(line: String): Long? {
    val fields = line.trim().split(' ').filter { it.isNotEmpty() }
    return fields.getOrNull(1)?.toLongOrNull()
}

/**
 * Process CPU use as a percentage of the whole device (all [cores]): CPU time spent between the two tick counts
 * over [elapsedMs] of wall time. Null when the window is empty; clamped to 0..100.
 */
internal fun cpuPercent(prevTicks: Long, nowTicks: Long, clkTck: Long, elapsedMs: Long, cores: Int): Double? {
    if (elapsedMs <= 0 || cores <= 0 || clkTck <= 0) return null
    val cpuSeconds = (nowTicks - prevTicks).toDouble() / clkTck
    val wallSeconds = elapsedMs / 1000.0
    return (cpuSeconds / (wallSeconds * cores) * 100.0).coerceIn(0.0, 100.0)
}
