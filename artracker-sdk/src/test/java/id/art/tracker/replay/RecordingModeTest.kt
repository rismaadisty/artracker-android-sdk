package id.art.tracker.replay

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
}
