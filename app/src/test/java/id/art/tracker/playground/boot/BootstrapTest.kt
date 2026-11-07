package id.art.tracker.playground.boot

import id.art.tracker.playground.settings.EndpointGuard
import id.art.tracker.playground.settings.EndpointProfile
import id.art.tracker.playground.settings.PlaygroundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapTest {
    private val real = "art_live_" + "r".repeat(43)
    private val remote = "https://artracker.exlm.my.id/"

    private fun settings(profile: EndpointProfile, local: String = "http://10.0.2.2:3000") =
        PlaygroundSettings.DEFAULT.copy(endpointProfile = profile, localEndpoint = local)

    @Test
    fun sinkWhenAvailableUsesTheSinkAndTheDummyKey() {
        val r = Bootstrap.resolve(settings(EndpointProfile.SINK), remote, real, grantConsumed = false, sinkAvailable = true)
        assertEquals(EndpointGuard.SINK_ENDPOINT, r.endpoint)
        assertEquals(EndpointGuard.DUMMY_KEY, r.apiKey)
        assertFalse(r.badge.remote)
        assertEquals(EndpointProfile.SINK, r.badge.profile)
        assertNull(r.notice)
    }

    @Test
    fun sinkUnavailableFallsBackToOfflineWithNotice() {
        val r = Bootstrap.resolve(settings(EndpointProfile.SINK), remote, real, grantConsumed = false, sinkAvailable = false)
        assertEquals(EndpointGuard.OFFLINE_ENDPOINT, r.endpoint)
        assertEquals(EndpointGuard.DUMMY_KEY, r.apiKey)
        assertEquals(EndpointProfile.OFFLINE, r.badge.profile)
        assertFalse(r.notice.isNullOrBlank())
    }

    @Test
    fun offlineUsesTheBlackHoleAndTheDummyKey() {
        val r = Bootstrap.resolve(settings(EndpointProfile.OFFLINE), remote, real, grantConsumed = true, sinkAvailable = true)
        assertEquals(EndpointGuard.OFFLINE_ENDPOINT, r.endpoint)
        assertEquals(EndpointGuard.DUMMY_KEY, r.apiKey)
        assertFalse(r.badge.remote)
    }

    @Test
    fun localUsesTheRealKeyWhenPresentElseDummy() {
        val withKey = Bootstrap.resolve(settings(EndpointProfile.LOCAL), remote, real, false, true)
        assertEquals("http://10.0.2.2:3000", withKey.endpoint)
        assertEquals(real, withKey.apiKey)
        assertFalse(withKey.badge.remote)
        val noKey = Bootstrap.resolve(settings(EndpointProfile.LOCAL), remote, "  ", false, true)
        assertEquals(EndpointGuard.DUMMY_KEY, noKey.apiKey)
    }

    @Test
    fun localWithARemoteHostFallsBackToSink() {
        val r = Bootstrap.resolve(settings(EndpointProfile.LOCAL, local = remote), remote, real, true, true)
        assertEquals(EndpointGuard.SINK_ENDPOINT, r.endpoint)
        assertEquals(EndpointGuard.DUMMY_KEY, r.apiKey)
        assertFalse(r.badge.remote)
        assertNotNull(r.notice)
    }

    @Test
    fun backendWithoutGrantFallsBackToSinkWithNotice() {
        val r = Bootstrap.resolve(settings(EndpointProfile.BACKEND), remote, real, grantConsumed = false, sinkAvailable = true)
        assertEquals(EndpointGuard.SINK_ENDPOINT, r.endpoint)
        assertEquals(EndpointGuard.DUMMY_KEY, r.apiKey)
        assertFalse(r.notice.isNullOrBlank())
        assertFalse(r.badge.remote)
    }

    @Test
    fun backendWithGrantButBlankKeyFallsBackToSink() {
        val r = Bootstrap.resolve(settings(EndpointProfile.BACKEND), remote, "", grantConsumed = true, sinkAvailable = true)
        assertEquals(EndpointGuard.SINK_ENDPOINT, r.endpoint)
        assertEquals(EndpointGuard.DUMMY_KEY, r.apiKey)
        assertFalse(r.notice.isNullOrBlank())
        assertFalse(r.badge.remote)
    }

    @Test
    fun backendWithGrantAndKeyIsTheOnlyPathToTheRealKeyAndRemoteEndpoint() {
        val r = Bootstrap.resolve(settings(EndpointProfile.BACKEND), remote, real, grantConsumed = true, sinkAvailable = true)
        assertEquals(remote, r.endpoint)
        assertEquals(real, r.apiKey)
        assertTrue(r.badge.remote)
        assertEquals("artracker.exlm.my.id", r.badge.host)
        assertEquals(EndpointProfile.BACKEND, r.badge.profile)
    }

    @Test
    fun backendWithALocalBuildEndpointNeedsNoGrant() {
        val r = Bootstrap.resolve(settings(EndpointProfile.BACKEND), "http://10.0.2.2:3000", real, grantConsumed = false, sinkAvailable = true)
        assertEquals("http://10.0.2.2:3000", r.endpoint)
        assertFalse(r.badge.remote)
        assertEquals(EndpointProfile.BACKEND, r.badge.profile)
    }

    @Test
    fun realKeyNeverAppearsForSinkOrOffline() {
        for (profile in listOf(EndpointProfile.SINK, EndpointProfile.OFFLINE)) {
            for (grant in listOf(true, false)) {
                for (sinkUp in listOf(true, false)) {
                    val r = Bootstrap.resolve(settings(profile), remote, real, grant, sinkUp)
                    assertEquals("$profile grant=$grant sink=$sinkUp", EndpointGuard.DUMMY_KEY, r.apiKey)
                    assertFalse(r.badge.remote)
                }
            }
        }
    }

    @Test
    fun shouldWipeOnlyWhenMovingIntoRemote() {
        assertTrue(Bootstrap.shouldWipe(resolvedRemote = true, lastRunRemote = false))
        assertFalse(Bootstrap.shouldWipe(resolvedRemote = true, lastRunRemote = true))
        assertFalse(Bootstrap.shouldWipe(resolvedRemote = false, lastRunRemote = false))
        assertFalse(Bootstrap.shouldWipe(resolvedRemote = false, lastRunRemote = true))
    }

    @Test
    fun discardedSettingsAddANoticeAndKeepAnyExistingOne() {
        val plain = Bootstrap.resolve(settings(EndpointProfile.SINK), remote, real, false, true)
        assertNull(plain.notice)
        assertEquals(plain, Bootstrap.withDiscardedNotice(plain, discarded = false))
        val withNotice = Bootstrap.withDiscardedNotice(plain, discarded = true)
        assertTrue(withNotice.notice!!.contains("Pengaturan tersimpan tidak valid"))
        assertEquals(plain.endpoint, withNotice.endpoint)

        val refused = Bootstrap.resolve(settings(EndpointProfile.BACKEND), remote, real, grantConsumed = false, sinkAvailable = true)
        val both = Bootstrap.withDiscardedNotice(refused, discarded = true).notice!!
        assertTrue(both.contains("Backend tidak diaktifkan") && both.contains("Pengaturan tersimpan tidak valid"))
    }
}
