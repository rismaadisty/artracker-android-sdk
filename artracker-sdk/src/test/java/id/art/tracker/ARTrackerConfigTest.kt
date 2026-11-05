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
        replay: ReplayConfig = ReplayConfig(),
        network: NetworkConfig = NetworkConfig(),
    ) = ARTrackerConfig(
        endpoint = endpoint,
        apiKey = apiKey,
        flushIntervalMs = flushIntervalMs,
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
    fun fps_isClamped() {
        assertEquals(4.0, ReplayConfig(framesPerSecond = 10.0).effectiveFps(), 0.0)
        assertEquals(0.5, ReplayConfig(framesPerSecond = 0.1).effectiveFps(), 0.0)
        assertEquals(1.0, ReplayConfig().effectiveFps(), 0.0)
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
}
