package id.art.tracker.playground.status

import id.art.tracker.Diagnostics
import id.art.tracker.playground.settings.PlaygroundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkStatusRepositoryTest {
    private fun repo(d: Diagnostics, optedOut: Boolean = false): SdkStatusRepository {
        val controls = SdkControls(FakeStore(optedOut), PlaygroundSettings.DEFAULT, FakeSwitches())
        return SdkStatusRepository({ d }, controls)
    }

    @Test
    fun statusMirrorsTheDiagnostics() {
        val s = repo(Diagnostics(true, "s1", 4, "accepted", false)).poll()
        assertTrue(s.initialized)
        assertEquals("s1", s.sessionId)
        assertEquals(4, s.pending)
        assertEquals("accepted", s.lastSend)
        assertFalse(s.disabled)
        assertTrue(s.replayIntent)
        assertFalse(s.optedOutIntent)
    }

    @Test
    fun disabledWithoutOptOutSuggestsTheKillSwitch() {
        assertTrue(repo(Diagnostics(true, "s", 0, "unauthorized", true), optedOut = false).poll().disabledByOtherThanOptOut)
        assertFalse(repo(Diagnostics(true, "s", 0, null, true), optedOut = true).poll().disabledByOtherThanOptOut)
        assertFalse(repo(Diagnostics(true, "s", 0, null, false), optedOut = false).poll().disabledByOtherThanOptOut)
    }

    @Test
    fun notInitializedIsReported() {
        val s = repo(Diagnostics(false, null, 0, null, false)).poll()
        assertFalse(s.initialized)
        assertNull(s.sessionId)
    }
}
