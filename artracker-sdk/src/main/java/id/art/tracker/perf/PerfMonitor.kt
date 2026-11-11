package id.art.tracker.perf

import id.art.tracker.TelemetryItem
import id.art.tracker.core.ItemFactory
import kotlin.math.roundToInt

/**
 * Owns the sample buffer. [tick] takes a sample; every [flushEvery] samples, on a session change and on [flush] the
 * buffer goes out as `perf.samples` item(s) of at most [MAX_SAMPLES] samples. Samples are always sent under the id of
 * the session they were taken in (the one that was current when the first of them was buffered).
 */
internal class PerfMonitor(
    private val sampler: PerfSampler,
    private val device: DeviceProbe,
    private val intervalMs: Long,
    /** The current session id, or null while there is none (nothing is sampled then). */
    private val sessionId: () -> String?,
    /** Hands an item to the pipeline; the core pins an [ItemFactory] to the given session id. */
    private val emit: (sessionId: String, item: (ItemFactory) -> TelemetryItem) -> Unit,
    private val flushEvery: Int = FLUSH_EVERY,
) {
    private val buffer = ArrayList<PerfSample>()
    private var bufferSession: String? = null
    private var lastThermal: String? = null

    @Synchronized
    fun tick() {
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

    /** Sampling resumes after the app was in the background: the next sample is a fresh baseline. */
    @Synchronized
    fun onForeground() {
        sampler.resetBaseline()
    }

    private fun deviceAttributes(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(3)
        device.ramMb()?.let { m["ram_mb"] = it }
        m["cores"] = device.cores()
        sampler.refreshHz?.let { m["refresh_hz"] = it.roundToInt() }
        return m
    }

    companion object {
        const val FLUSH_EVERY = 10
        const val MAX_SAMPLES = 60
    }
}
