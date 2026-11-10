package id.art.tracker.playground.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaygroundSettingsTest {
    private val key = "art_live_" + "a".repeat(43)
    private val remote = "https://artracker.exlm.my.id/"

    @Test
    fun defaultProfileIsSink() {
        assertEquals(EndpointProfile.SINK, PlaygroundSettings.DEFAULT.endpointProfile)
    }

    @Test
    fun defaultConfigMatchesLegacyValues() {
        val c = PlaygroundSettings.DEFAULT.toConfig("http://localhost:1", key, debug = false)
        assertEquals("sample", c.environment)
        assertTrue(c.replay.enabled)
        assertEquals(1.0, c.replay.sampleRate, 0.0)
        assertEquals(1.0, c.replay.framesPerSecond, 0.0)
        assertTrue(c.privacy.maskAllInputs)
        assertEquals(10_000L, c.flushIntervalMs)
        assertEquals(1_800_000L, c.sessionTimeoutMs)
        assertEquals("http://localhost:1", c.endpoint)
        assertFalse(c.debug)
    }

    @Test
    fun validateRejectsEachBoundary() {
        val d = PlaygroundSettings.DEFAULT
        assertEquals(1, d.copy(flushIntervalMs = 999).validate().size)
        assertTrue(d.copy(flushIntervalMs = 1000).validate().isEmpty())
        assertEquals(1, d.copy(sessionTimeoutMs = 999).validate().size)
        assertTrue(d.copy(sessionTimeoutMs = 1000).validate().isEmpty())
        assertEquals(1, d.copy(replaySampleRate = 1.0001).validate().size)
        assertEquals(1, d.copy(replaySampleRate = -0.1).validate().size)
        assertTrue(d.copy(replaySampleRate = 0.0).validate().isEmpty())
        assertTrue(d.copy(replaySampleRate = 1.0).validate().isEmpty())
        assertEquals(1, d.copy(networkSampleRate = 1.0001).validate().size)
        assertEquals(1, d.copy(networkSampleRate = -0.1).validate().size)
        assertTrue(d.copy(networkSampleRate = 0.0).validate().isEmpty())
        assertTrue(d.copy(networkSampleRate = 1.0).validate().isEmpty())
        assertEquals(1, d.copy(replayFps = Double.NaN).validate().size)
        assertTrue(d.copy(replayFps = 0.1).validate().isEmpty())
    }

    @Test
    fun localProfileRejectsARemoteHost() {
        val bad = PlaygroundSettings.DEFAULT.copy(endpointProfile = EndpointProfile.LOCAL, localEndpoint = remote)
        assertTrue(bad.validate().any { it.contains("Backend") })
        val ok = PlaygroundSettings.DEFAULT.copy(endpointProfile = EndpointProfile.LOCAL, localEndpoint = "http://10.0.2.2:3000")
        assertTrue(ok.validate().isEmpty())
    }

    @Test
    fun endpointForPicksTheProfile() {
        val d = PlaygroundSettings.DEFAULT
        assertEquals(EndpointGuard.SINK_ENDPOINT, d.copy(endpointProfile = EndpointProfile.SINK).endpointFor(remote))
        assertEquals(EndpointGuard.OFFLINE_ENDPOINT, d.copy(endpointProfile = EndpointProfile.OFFLINE).endpointFor(remote))
        assertEquals(d.localEndpoint, d.copy(endpointProfile = EndpointProfile.LOCAL).endpointFor(remote))
        assertEquals(remote, d.copy(endpointProfile = EndpointProfile.BACKEND).endpointFor(remote))
        assertTrue(d.copy(endpointProfile = EndpointProfile.BACKEND).isRemote(remote))
        assertFalse(d.copy(endpointProfile = EndpointProfile.BACKEND).isRemote("http://10.0.2.2:3000"))
        assertFalse(d.copy(endpointProfile = EndpointProfile.SINK).isRemote(remote))
        assertFalse(d.copy(endpointProfile = EndpointProfile.OFFLINE).isRemote(remote))
        assertFalse(d.copy(endpointProfile = EndpointProfile.LOCAL).isRemote(remote))
    }

    @Test
    fun presetsAreValidAndDistinct() {
        SettingsPreset.values().forEach {
            val applied = it.apply(PlaygroundSettings.DEFAULT)
            assertTrue("$it", applied.validate().isEmpty())
            assertEquals("$it must not change the profile", EndpointProfile.SINK, applied.endpointProfile)
        }
        val strict = SettingsPreset.PRIVACY_STRICT.apply(PlaygroundSettings.DEFAULT)
        assertTrue(strict.maskAllText && strict.maskAllInputs)
        val fast = SettingsPreset.DEBUG_FAST.apply(PlaygroundSettings.DEFAULT)
        assertEquals(1_000L, fast.flushIntervalMs)
        assertEquals(10_000L, fast.sessionTimeoutMs)
        val low = SettingsPreset.LOW_BANDWIDTH.apply(PlaygroundSettings.DEFAULT)
        assertEquals(0.1, low.replaySampleRate, 0.0)
        assertTrue(low.captureOnlyErrors)
        assertEquals(30_000L, low.flushIntervalMs)
    }

    @Test
    fun theDefaultIsWireframes_andScreenshotsAreAnExplicitOptIn() {
        val d = PlaygroundSettings.DEFAULT.toConfig("http://localhost:1", key, debug = false)
        assertEquals(id.art.tracker.ReplayMode.WIREFRAME, d.replay.mode)
        val c = PlaygroundSettings.DEFAULT.copy(
            replayMode = id.art.tracker.ReplayMode.SCREENSHOT, replayQuality = id.art.tracker.ReplayQuality.LOW, replayFps = 2.0,
        ).toConfig("http://localhost:1", key, debug = false)
        assertEquals(id.art.tracker.ReplayMode.SCREENSHOT, c.replay.mode)
        assertEquals(id.art.tracker.ReplayQuality.LOW, c.replay.quality)
        assertEquals(2.0, c.replay.framesPerSecond, 0.0)
    }
}
