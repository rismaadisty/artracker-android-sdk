package id.art.tracker.replay

import id.art.tracker.PerformanceConfig
import id.art.tracker.ReplayConfig

/** Appended to the session id so the on-error sampling decision is independent of the full-session one. */
internal const val ON_ERROR_SALT = ":on-error"

/** Salts for the performance decisions: independent of each other and of the replay ones for the same session id. */
internal const val PERF_SALT = ":perf"
internal const val PERF_ON_ERROR_SALT = ":perf-on-error"

/** How a session's replay is recorded: [FULL] uploads everything, [BUFFER] holds chunks until an error. */
internal enum class RecordingMode {
    FULL, BUFFER;

    companion object
}

/** Null = do not record. Does not look at `enabled`; the caller does. */
internal fun RecordingMode.Companion.select(
    sessionId: String,
    manual: Boolean,
    config: ReplayConfig,
    sampler: (String, Double) -> Boolean,
): RecordingMode? = when {
    manual -> RecordingMode.FULL
    sampler(sessionId, config.sampleRate) -> RecordingMode.FULL
    sampler(sessionId + ON_ERROR_SALT, config.onErrorSampleRate) -> RecordingMode.BUFFER
    else -> null
}

/** Null = do not sample performance. Does not look at `enabled`; the caller does. */
internal fun RecordingMode.Companion.selectPerf(
    sessionId: String,
    config: PerformanceConfig,
    sampler: (String, Double) -> Boolean,
): RecordingMode? = when {
    sampler(sessionId + PERF_SALT, config.sampleRate) -> RecordingMode.FULL
    sampler(sessionId + PERF_ON_ERROR_SALT, config.onErrorSampleRate) -> RecordingMode.BUFFER
    else -> null
}
