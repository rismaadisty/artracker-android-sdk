package id.art.tracker.playground.labs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LabGuardTest {
    @Test
    fun loadAndFakePiiAreBlockedOnARemoteEndpoint() {
        assertFalse(LabGuard.disabledReason(LabActionKind.FAKE_PII, remote = true).isNullOrBlank())
        assertFalse(LabGuard.disabledReason(LabActionKind.LOAD, remote = true).isNullOrBlank())
    }

    @Test
    fun normalAndCrashAreNeverBlockedByTheGuard() {
        assertNull(LabGuard.disabledReason(LabActionKind.NORMAL, remote = true))
        assertNull(LabGuard.disabledReason(LabActionKind.CRASH, remote = true))
    }

    @Test
    fun nothingIsBlockedOnALocalEndpoint() {
        LabActionKind.values().forEach { assertNull("$it", LabGuard.disabledReason(it, remote = false)) }
    }
}
