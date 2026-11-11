package id.art.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ARTrackerConfigTest {
    private val endpoint = "https://art.exlm.my.id"
    private val validKey = "art_live_" + "a".repeat(43)

    private fun config(
        endpoint: String = this.endpoint,
        apiKey: String = validKey,
        flushIntervalMs: Long = 10_000,
        sessionTimeoutMs: Long = 1_800_000,
        replay: ReplayConfig = ReplayConfig(),
        network: NetworkConfig = NetworkConfig(),
    ) = ARTrackerConfig(
        endpoint = endpoint,
        apiKey = apiKey,
        flushIntervalMs = flushIntervalMs,
        sessionTimeoutMs = sessionTimeoutMs,
        replay = replay,
        network = network,
    )

    private fun keyOfLength(n: Int) = "art_live_" + "a".repeat(n - "art_live_".length)

    @Test
    fun validConfig_hasNoErrors() {
        assertTrue(config().validate().isEmpty())
    }

    @Test
    fun secretKey_isRejected() {
        val errors = config(apiKey = "art_sec_" + "a".repeat(43)).validate()
        assertTrue(errors.any { it.contains("art_live_") })
    }

    @Test
    fun keyLength_bounds() {
        assertFalse(config(apiKey = keyOfLength(31)).validate().isEmpty())
        assertFalse(config(apiKey = keyOfLength(129)).validate().isEmpty())
        assertTrue(config(apiKey = keyOfLength(32)).validate().isEmpty())
        assertTrue(config(apiKey = keyOfLength(128)).validate().isEmpty())
    }

    @Test
    fun endpoint_mustBeHttp() {
        assertFalse(config(endpoint = "ftp://x").validate().isEmpty())
        assertFalse(config(endpoint = "").validate().isEmpty())
        assertTrue(config(endpoint = "http://localhost:3000").validate().isEmpty())
    }

    @Test
    fun flushInterval_below1000_isError() {
        assertFalse(config(flushIntervalMs = 999).validate().isEmpty())
        assertTrue(config(flushIntervalMs = 1000).validate().isEmpty())
    }

    @Test
    fun sampleRate_outside0to1_isError() {
        assertFalse(config(replay = ReplayConfig(sampleRate = 1.1)).validate().isEmpty())
        assertFalse(config(replay = ReplayConfig(sampleRate = -0.1)).validate().isEmpty())
        assertFalse(config(network = NetworkConfig(sampleRate = 1.5)).validate().isEmpty())
        assertFalse(config(network = NetworkConfig(sampleRate = -1.0)).validate().isEmpty())
        assertTrue(config(replay = ReplayConfig(sampleRate = 1.0), network = NetworkConfig(sampleRate = 0.0)).validate().isEmpty())
    }

    @Test
    fun fps_nonFinite_effectiveFpsFallsBackToOne() {
        assertEquals(1.0, ReplayConfig(framesPerSecond = Double.NaN).effectiveFps(), 0.0)
        assertEquals(1.0, ReplayConfig(framesPerSecond = Double.POSITIVE_INFINITY).effectiveFps(), 0.0)
        assertEquals(1.0, ReplayConfig(framesPerSecond = Double.NEGATIVE_INFINITY).effectiveFps(), 0.0)
    }

    @Test
    fun replayDefaults_areOff_withStandardQuality() {
        val r = ReplayConfig()
        assertEquals(ReplayQuality.STANDARD, r.quality)
        assertFalse(r.enabled)
    }

    @Test
    fun fps_isClampedTo0_5Through10() {
        assertEquals(10.0, ReplayConfig(framesPerSecond = 30.0).effectiveFps(), 0.0)
        assertEquals(8.0, ReplayConfig(framesPerSecond = 8.0).effectiveFps(), 0.0)
        assertEquals(0.5, ReplayConfig(framesPerSecond = 0.1).effectiveFps(), 0.0)
        assertEquals(1.0, ReplayConfig(framesPerSecond = Double.NaN).effectiveFps(), 0.0)
        assertEquals(1.0, ReplayConfig().effectiveFps(), 0.0)
    }

    @Test
    fun qualityPresets_areShortSidePixelsAndJpegQuality() {
        assertEquals(480 to 50, ReplayQuality.LOW.preset().let { it.shortSidePx to it.jpegQuality })
        assertEquals(720 to 60, ReplayQuality.STANDARD.preset().let { it.shortSidePx to it.jpegQuality })
        assertEquals(1080 to 75, ReplayQuality.HIGH.preset().let { it.shortSidePx to it.jpegQuality })
    }

    @Test
    fun qualityLower_stepsDownOnePresetAndStopsAtLow() {
        assertEquals(ReplayQuality.STANDARD, ReplayQuality.HIGH.lower())
        assertEquals(ReplayQuality.LOW, ReplayQuality.STANDARD.lower())
        assertEquals(null, ReplayQuality.LOW.lower())
    }

    @Test
    fun fps_nonFinite_isAValidationError() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val errors = config(replay = ReplayConfig(framesPerSecond = bad)).validate()
            assertTrue("fps $bad: $errors", errors.any { it.contains("framesPerSecond") })
        }
        // Finite out-of-range values are still just clamped.
        assertTrue(config(replay = ReplayConfig(framesPerSecond = 100.0)).validate().isEmpty())
        assertTrue(config(replay = ReplayConfig(framesPerSecond = 0.0)).validate().isEmpty())
    }

    @Test
    fun sessionTimeout_below1000_isError() {
        assertTrue(config(sessionTimeoutMs = 0).validate().any { it.contains("sessionTimeoutMs") })
        assertTrue(config(sessionTimeoutMs = -5).validate().any { it.contains("sessionTimeoutMs") })
        assertTrue(config(sessionTimeoutMs = 999).validate().any { it.contains("sessionTimeoutMs") })
        assertTrue(config(sessionTimeoutMs = 1_000).validate().isEmpty())
    }

    @Test
    fun apiKey_controlCharacters_areRejected_withoutEchoingTheKey() {
        val padding = "a".repeat(40)
        for (bad in listOf("\r", "\n", "\u0000", "\t", "\u001f", "\u007f")) {
            val key = "art_live_${padding}${bad}INJECTED"
            val errors = config(apiKey = key).validate()
            assertTrue("control char ${bad.first().code}: $errors", errors.any { it.contains("control characters") })
            assertTrue(errors.toString(), errors.none { it.contains(padding) || it.contains("INJECTED") })
        }
        assertTrue(config(apiKey = "art_live_" + "a-_Z9".repeat(9)).validate().isEmpty())
    }

    @Test
    fun javaBuilder_matchesDefaults() {
        val c = ARTrackerConfig.Builder(endpoint, validKey).build()
        assertFalse(c.replay.enabled)
        assertTrue(c.privacy.maskAllInputs)
        assertEquals("production", c.environment)
        assertEquals(10_000L, c.flushIntervalMs)
        assertEquals(1_800_000L, c.sessionTimeoutMs)
    }

    @Test
    fun javaBuilder_appliesSetters() {
        val c = ARTrackerConfig.Builder(endpoint, validKey)
            .environment("staging")
            .appVersion("1.2.3")
            .debug(true)
            .flushIntervalMs(5_000)
            .build()
        assertEquals("staging", c.environment)
        assertEquals("1.2.3", c.appVersion)
        assertTrue(c.debug)
        assertEquals(5_000L, c.flushIntervalMs)
    }

    @Test
    fun performance_isOffByDefault() {
        assertFalse(config().performance.enabled)
    }

    @Test
    fun builder_setsPerformance() {
        val built = ARTrackerConfig.Builder(endpoint, validKey)
            .performance(PerformanceConfig(enabled = true))
            .build()
        assertTrue(built.performance.enabled)
    }

    @Test
    fun sampleInterval_outsideRange_isError() {
        fun errors(ms: Long) = ARTrackerConfig(endpoint, validKey, performance = PerformanceConfig(sampleIntervalMs = ms)).validate()
        assertTrue(errors(499).any { it.contains("sampleIntervalMs") })
        assertTrue(errors(10_001).any { it.contains("sampleIntervalMs") })
        assertTrue(errors(500).none { it.contains("sampleIntervalMs") })
        assertTrue(errors(10_000).none { it.contains("sampleIntervalMs") })
    }
}
