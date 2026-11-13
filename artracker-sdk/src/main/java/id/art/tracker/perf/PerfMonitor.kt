package id.art.tracker.perf

import id.art.tracker.TelemetryItem
import id.art.tracker.core.ItemFactory
import id.art.tracker.core.SdkLog
import kotlin.math.roundToInt

/**
 * Owns the sample buffer. [tick] takes a sample; every [flushEvery] samples, on a session change and on [flush] the
 * buffer goes out as `perf.samples` item(s) of at most [MAX_SAMPLES] samples. Samples are always sent under the id of
 * the session they were taken in (the one that was current when the first of them was buffered).
 */
internal class PerfMonitor(
    private val sampler: PerfSampler,
    private val device: DeviceProbe,
    /** Started and stopped with sampling; only [PerfSampler] reads it. */
    private val network: NetworkProbe,
    private val intervalMs: Long,
    /** The current session id, or null while there is none (nothing is sampled then). */
    private val sessionId: () -> String?,
    /** Hands an item to the pipeline; the core pins an [ItemFactory] to the given session id. */
    private val emit: (sessionId: String, item: (ItemFactory) -> TelemetryItem) -> Unit,
    private val flushEvery: Int = FLUSH_EVERY,
    /**
     * Also warn when the system reports low memory in a sample (`ActivityManager.MemoryInfo.lowMemory`). Meant for
     * Android 14+, where `onTrimMemory` no longer delivers the running-low levels; below that the trim callback is
     * the only source, so there is never a second warning for the same pressure.
     */
    private val memoryInfoWarnings: Boolean = false,
) {
    private val buffer = ArrayList<PerfSample>()
    private var bufferSession: String? = null
    private var lastThermal: String? = null
    private var lastLow = false

    /** False between [onBackground] / [discard] and the next [onForeground]: a tick already in flight then does nothing. */
    private var running = true

    @Synchronized
    fun tick() {
        if (!running) return
        val sid = sessionId() ?: return
        if (bufferSession != null && bufferSession != sid) flush()
        val sample = sampler.sample()
        bufferSession = sid
        buffer += sample

        val thermal = sample.thermal
        if (thermal != null) {
            val previous = lastThermal
            if (previous != null && previous != thermal) {
                emit(sid) { it.event("perf.thermal_change", mapOf("from" to previous, "to" to thermal)) }
            }
            lastThermal = thermal
        }
        if (memoryInfoWarnings) {
            val low = sample.lowMemory == true
            if (low && !lastLow) {
                val freeMb = sample.ramFreeMb
                emit(sid) {
                    it.event("perf.memory_warning", mapOf("source" to "memory_info", "ram_free_mb" to freeMb))
                }
            }
            lastLow = low
        }
        if (buffer.size >= flushEvery) flush()
    }

    /** Emits the buffered samples (no-op when there are none). */
    @Synchronized
    fun flush() {
        val sid = bufferSession
        if (buffer.isEmpty() || sid == null) {
            buffer.clear()
            return
        }
        val batch = ArrayList(buffer)
        buffer.clear()
        val deviceInfo = deviceAttributes()
        for (chunk in batch.chunked(MAX_SAMPLES)) {
            val first = chunk.first().t
            val attrs = linkedMapOf<String, Any?>(
                "v" to 1,
                "platform" to "android",
                "interval_ms" to intervalMs,
                "device" to deviceInfo,
                "samples" to chunk.map { it.toMap() },
            )
            emit(sid) { it.perf(attrs).copy(timestampMs = first) }
        }
    }

    /** Sampling (re)starts: the frame source starts and the next sample is a fresh baseline. */
    @Synchronized
    fun onForeground() {
        running = true
        lastLow = false
        runCatching { network.start() }.onFailure { SdkLog.w("starting the network probe failed", it) }
        sampler.start()
        sampler.resetBaseline()
    }

    /** Sampling stops: the last samples go out and the frame source shuts down. */
    @Synchronized
    fun onBackground() {
        flush()
        running = false
        runCatching { network.stop() }.onFailure { SdkLog.w("stopping the network probe failed", it) }
        sampler.stop()
    }

    /** Drops the buffered samples without sending them (the user opted out). */
    @Synchronized
    fun discard() {
        running = false
        buffer.clear()
    }

    private fun deviceAttributes(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(5)
        device.osVersion()?.takeIf { it.isNotBlank() }?.let { m["os_version"] = it.take(MAX_OS_VERSION) }
        device.ramMb()?.let { m["ram_mb"] = it }
        device.storageTotalMb()?.let { m["storage_mb"] = it }
        m["cores"] = device.cores()
        sampler.refreshHz?.let { m["refresh_hz"] = it.roundToInt() }
        return m
    }

    companion object {
        /**
         * Whether samples should also warn on `MemoryInfo.lowMemory`: from Android 14 (API 34), where `onTrimMemory` no
         * longer delivers the running-low levels. Below it the trim callback stays the only source.
         */
        fun memoryInfoWarningsFor(sdkInt: Int): Boolean = sdkInt >= 34

        const val FLUSH_EVERY = 10
        const val MAX_SAMPLES = 60
        private const val MAX_OS_VERSION = 32
    }
}
