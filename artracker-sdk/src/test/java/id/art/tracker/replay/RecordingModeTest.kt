package id.art.tracker.replay

import id.art.tracker.PerformanceConfig
import id.art.tracker.ReplayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingModeTest {
    private val always = { _: String, _: Double -> true }
    private val never = { _: String, _: Double -> false }
    private val onlyOnError = { id: String, _: Double -> id.endsWith(ON_ERROR_SALT) }

    @Test fun manual_isFull() = assertEquals(RecordingMode.FULL, RecordingMode.select("s", true, ReplayConfig(), never))
    @Test fun sampleRateHit_isFull() = assertEquals(RecordingMode.FULL, RecordingMode.select("s", false, ReplayConfig(), always))
    @Test fun onlyOnErrorHit_isBuffer() = assertEquals(RecordingMode.BUFFER, RecordingMode.select("s", false, ReplayConfig(), onlyOnError))
    @Test fun noHit_isNull() = assertNull(RecordingMode.select("s", false, ReplayConfig(), never))
    @Test fun bufferUsesSaltedId_andOnErrorRate() {
        val calls = mutableListOf<Pair<String, Double>>()
        RecordingMode.select("s", false, ReplayConfig(sampleRate = 0.2, onErrorSampleRate = 0.7)) { id, r -> calls += id to r; false }
        assertEquals(listOf("s" to 0.2, "s$ON_ERROR_SALT" to 0.7), calls)
    }

    private val onlyPerfOnError = { id: String, _: Double -> id.endsWith(PERF_ON_ERROR_SALT) }

    @Test fun perf_sampleRateHit_isFull() =
        assertEquals(RecordingMode.FULL, RecordingMode.selectPerf("s", PerformanceConfig(), always))
    @Test fun perf_onlyOnErrorHit_isBuffer() =
        assertEquals(RecordingMode.BUFFER, RecordingMode.selectPerf("s", PerformanceConfig(), onlyPerfOnError))
    @Test fun perf_noHit_isNull() = assertNull(RecordingMode.selectPerf("s", PerformanceConfig(), never))
    @Test fun perf_usesPerfSalts_andRates() {
        val calls = mutableListOf<Pair<String, Double>>()
        RecordingMode.selectPerf("s", PerformanceConfig(sampleRate = 0.2, onErrorSampleRate = 0.7)) { id, r -> calls += id to r; false }
        assertEquals(listOf("s$PERF_SALT" to 0.2, "s$PERF_ON_ERROR_SALT" to 0.7), calls)
    }
    @Test fun perf_saltsDifferFromReplaySalts() {
        // A session's perf decision must be independent of its replay decision.
        assertEquals(":perf", PERF_SALT)
        assertEquals(":perf-on-error", PERF_ON_ERROR_SALT)
    }
}
