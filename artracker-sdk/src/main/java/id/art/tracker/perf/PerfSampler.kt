package id.art.tracker.perf

import id.art.tracker.core.Clock

private const val BYTES_PER_MB = 1_048_576.0

/**
 * Takes one [PerfSample] per call. CPU % and fps are rates, so the sampler keeps the previous tick count / drain time:
 * the first sample after construction or [resetBaseline] has no `cpu` and no `fps`. Nothing here throws: a `/proc` file
 * that cannot be read (some OEM SELinux policies) just leaves `cpu`, `mem_mb` and `threads` out of the sample.
 */
internal class PerfSampler(
    private val reader: ProcessReader,
    private val frames: FrameSource?,
    private val device: DeviceProbe,
    private val clock: Clock,
    private val monotonicMs: () -> Long,
) {
    private var prevTicks: Long? = null
    private var prevTicksAtMs = 0L
    private var lastDrainAtMs: Long? = null

    /** Refresh rate of the last frame window; null while there is no frame source. */
    @Volatile
    var refreshHz: Float? = null
        private set

    @Synchronized
    fun sample(): PerfSample {
        val t = clock.nowMs()
        val mono = monotonicMs()

        val stat = runCatching { reader.readStat()?.let(::parseProcStat) }.getOrNull()
        var cpu: Double? = null
        if (stat != null) {
            val ticks = stat.utimeTicks + stat.stimeTicks
            prevTicks?.let { prev ->
                cpu = cpuPercent(prev, ticks, device.clkTck(), mono - prevTicksAtMs, device.cores())
            }
            prevTicks = ticks
            prevTicksAtMs = mono
        }

        val pages = runCatching { reader.readStatm()?.let(::parseStatmResidentPages) }.getOrNull()
        val memMb = pages?.let { it * device.pageSizeBytes() / BYTES_PER_MB }

        val window = frames?.let { runCatching { it.drain() }.getOrNull() }
        val drainedAt = lastDrainAtMs
        val fps = if (window != null && drainedAt != null) window.fps(mono - drainedAt) else null
        if (window != null) {
            lastDrainAtMs = mono
            refreshHz = window.refreshHz
        }

        return PerfSample(
            t = t,
            cpu = cpu,
            memMb = memMb,
            fps = fps,
            jank = window?.jank,
            frozen = window?.frozen,
            threads = stat?.threads,
            battery = runCatching { device.batteryFraction() }.getOrNull(),
            thermal = runCatching { device.thermal() }.getOrNull(),
        )
    }

    /**
     * Called when sampling resumes after a pause (the app was in the background): the tick counts and the frames
     * counted since then belong to a window that would be far longer than one interval.
     */
    @Synchronized
    fun resetBaseline() {
        prevTicks = null
        lastDrainAtMs = null
        frames?.let { runCatching { it.drain() } }
    }
}
