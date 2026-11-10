package id.art.tracker.replay

import id.art.tracker.testutil.InMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayRemoteConfigCacheTest {
    @Test
    fun emptyCache_knowsNothing() {
        val c = ReplayRemoteConfigCache(InMemoryStore())
        assertNull(c.screenshotEnabled)
        assertNull(c.maxFps)
    }

    @Test
    fun saved_survivesANewInstanceOverTheSameStore() {
        val store = InMemoryStore()
        ReplayRemoteConfigCache(store).save(RemoteReplayConfig(screenshotEnabled = true, maxFps = 2.5))
        val again = ReplayRemoteConfigCache(store)
        assertEquals(true, again.screenshotEnabled)
        assertEquals(2.5, again.maxFps!!, 0.0)
    }

    @Test
    fun disabled_isRemembered_asFalseNotUnknown() {
        val store = InMemoryStore()
        ReplayRemoteConfigCache(store).save(RemoteReplayConfig(screenshotEnabled = false, maxFps = null))
        assertEquals(false, ReplayRemoteConfigCache(store).screenshotEnabled)
        assertNull(ReplayRemoteConfigCache(store).maxFps)
    }

    @Test
    fun markDisabled_keepsTheFpsAndFlipsTheSwitch() {
        val store = InMemoryStore()
        val c = ReplayRemoteConfigCache(store)
        c.save(RemoteReplayConfig(true, 4.0))
        c.markScreenshotDisabled()
        assertFalse(c.screenshotEnabled!!)
        assertTrue(c.maxFps == 4.0)
    }
}
