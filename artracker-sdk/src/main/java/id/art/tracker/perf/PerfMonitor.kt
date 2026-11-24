package id.art.tracker.perf

import android.os.SystemClock
import id.art.tracker.PerformanceConfig
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ItemFactory
import id.art.tracker.core.SdkLog
import id.art.tracker.replay.ErrorTriggerPolicy
import id.art.tracker.replay.RecordingMode
import id.art.tracker.replay.Sampling
import id.art.tracker.replay.selectPerf
import kotlin.math.roundToInt

/**
 * Owns the sample buffer. [tick] takes a sample; every [flushEvery] samples, on a session change and on [flush] the
 * buffer goes out as `perf.samples` item(s) of at most [MAX_SAMPLES] samples. Samples are always sent under the id of
 * the session they were taken in (the one that was current when the first of them was buffered).
 *
 * Each session is profiled in one [RecordingMode], chosen once from [config] ([selectPerf]): FULL sends everything as
 * it goes, BUFFER emits the batches *held* (kept on the device, not sent), and null takes no samples at all. In BUFFER
 * mode an error ([onError]) that [ErrorTriggerPolicy] admits flushes the open samples, promotes the session's held
 * batches for upload ([onPromoteSession]) and keeps batches ready for [FOLLOW_UP_MS]. A BUFFER session that is left
 * without a trigger has its held batches dropped ([onDropSession]).
 */
internal class PerfMonitor(
    private val sampler: PerfSampler,
    private val device: DeviceProbe,
    /** Started and stopped with sampling; only [PerfSampler] reads it. */
    private val network: NetworkProbe,
    private val intervalMs: Long,
    /** The current session id, or null while there is none (nothing is sampled then). */
    private val sessionId: () -> String?,
    /**
     * Hands an item to the pipeline; the core pins an [ItemFactory] to the given session id. [held] items are stored
     * but not sent until [onPromoteSession].
     */
    private val emit: (sessionId: String, held: Boolean, item: (ItemFactory) -> TelemetryItem) -> Unit,
    private val flushEvery: Int = FLUSH_EVERY,
    /**
     * Also warn when the system reports low memory in a sample (`ActivityManager.MemoryInfo.lowMemory`). Meant for
     * Android 14+, where `onTrimMemory` no longer delivers the running-low levels; below that the trim callback is
     * the only source, so there is never a second warning for the same pressure.
     */
    private val memoryInfoWarnings: Boolean = false,
    /** Decides each session's mode. The default samples every session in full, which is what a bare monitor does. */
    private val config: PerformanceConfig = PerformanceConfig(enabled = true, sampleRate = 1.0),
    private val samplerFn: (String, Double) -> Boolean = Sampling::isSampled,
    /** A clock for the follow-up window and the error rate limit; it must not run backwards. */
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    /** Makes the held batches of a session uploadable (an error was admitted). */
    private val onPromoteSession: (sessionId: String) -> Unit = {},
    /** Deletes the held batches of a session that ended without an error. */
    private val onDropSession: (sessionId: String) -> Unit = {},
) {
    private val buffer = ArrayList<PerfSample>()
    private var bufferSession: String? = null
    private var lastThermal: String? = null
    private var lastLow = false
    private var lastMemoryWarningAtMs: Long? = null

    /** The session [mode] was chosen for, and that mode (null: not sampled). Both are decided on a session's first tick. */
    private var modeSessionId: String? = null
    private var mode: RecordingMode? = null

    /** Until this time ([nowMs]) batches of a BUFFER session are ready, not held. */
    private var followUpUntilMs = Long.MIN_VALUE
    private val errorPolicy = ErrorTriggerPolicy()

    /** False between [onBackground] / [discard] and the next [onForeground]: a tick already in flight then does nothing. */
    private var running = true

    @Synchronized
    fun tick() {
        if (!running) return
        val sid = sessionId() ?: return
        if (bufferSession != null && bufferSession != sid) flush()
        if (sid != modeSessionId) enterSession(sid)
        if (mode == null) return
        val sample = sampler.sample()
        bufferSession = sid
        buffer += sample

        val thermal = sample.thermal
        if (thermal != null) {
            val previous = lastThermal
            if (previous != null && previous != thermal) {
                emit(sid, isHeld()) { it.event("perf.thermal_change", mapOf("from" to previous, "to" to thermal)) }
            }
            lastThermal = thermal
        }
        if (memoryInfoWarnings) {
            val low = sample.lowMemory == true
            // The system flag flips as it kills cached processes: at most one warning per cooldown, so a device that
            // hovers at the threshold does not turn every sample into an event.
            val last = lastMemoryWarningAtMs
            if (low && !lastLow && (last == null || sample.t - last >= MEMORY_WARNING_COOLDOWN_MS)) {
                lastMemoryWarningAtMs = sample.t
                val freeMb = sample.ramFreeMb
                emit(sid, isHeld()) {
                    it.event("perf.memory_warning", mapOf("source" to "memory_info", "ram_free_mb" to freeMb))
                }
            }
            lastLow = low
        }
        if (buffer.size >= flushEvery) flush()
    }

    /**
     * A session's first tick: the previous BUFFER session ended without a trigger (its held batches are of no use any
     * more), and the new one gets its mode. Its samples were flushed by the caller before.
     */
    private fun enterSession(sid: String) {
        val previous = modeSessionId
        if (previous != null && mode == RecordingMode.BUFFER) {
            runCatching { onDropSession(previous) }.onFailure { SdkLog.w("dropping held perf samples failed", it) }
        }
        mode = RecordingMode.selectPerf(sid, config, samplerFn)
        modeSessionId = sid
        followUpUntilMs = Long.MIN_VALUE
    }

    /** Whether what is emitted now is held: BUFFER mode, outside a follow-up window. */
    private fun isHeld(): Boolean = mode == RecordingMode.BUFFER && nowMs() >= followUpUntilMs

    /**
     * An error was recorded. In BUFFER mode, if [ErrorTriggerPolicy] admits it: the samples taken so far go out held,
     * the session's held batches are promoted for upload, and the next [FOLLOW_UP_MS] of batches are ready at once.
     * Does nothing in FULL mode (everything is sent anyway), for a session that is not sampled, or before this
     * session's first sample (its mode is not decided yet, and the held batches belong to the previous session).
     */
    @Synchronized
    fun onError() {
        if (mode != RecordingMode.BUFFER) return
        val sid = modeSessionId ?: return
        if (sessionId() != sid) return
        val now = nowMs()
        if (!errorPolicy.admit(sid, now)) return
        flush() // still held (the follow-up window opens only now), then promoted with the rest
        followUpUntilMs = now + FOLLOW_UP_MS
        runCatching { onPromoteSession(sid) }.onFailure { SdkLog.w("promoting held perf samples failed", it) }
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
        val held = isHeld()
        for (chunk in batch.chunked(MAX_SAMPLES)) {
            val first = chunk.first().t
            val attrs = linkedMapOf<String, Any?>(
                "v" to 1,
                "platform" to "android",
                "interval_ms" to intervalMs,
                "device" to deviceInfo,
                "samples" to chunk.map { it.toMap() },
            )
            emit(sid, held) { it.perf(attrs).copy(timestampMs = first) }
        }
    }

    /** Sampling (re)starts: the frame source starts and the next sample is a fresh baseline. */
    @Synchronized
    fun onForeground() {
        running = true
        lastLow = false
        lastMemoryWarningAtMs = null
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

        /** Minimum time between two `perf.memory_warning` events from `MemoryInfo.lowMemory`. */
        const val MEMORY_WARNING_COOLDOWN_MS = 60_000L

        /** How long after an admitted error batches are ready instead of held. */
        const val FOLLOW_UP_MS = 30_000L

        const val FLUSH_EVERY = 10
        const val MAX_SAMPLES = 60
        private const val MAX_OS_VERSION = 32
    }
}
