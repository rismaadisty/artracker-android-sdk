package id.art.tracker.core

import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.InMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KillSwitchTest {
    private val minute = 60_000L
    private val clock = FakeClock(1_700_000_000_000L)
    private val store = InMemoryStore()

    @Test
    fun inactiveByDefault() {
        assertFalse(KillSwitch(store, clock).isActive())
    }

    @Test
    fun trip_activeForOneHour() {
        val k = KillSwitch(store, clock)
        k.trip()
        assertTrue(k.isActive())
        clock.advance(59 * minute)
        assertTrue(k.isActive())
        clock.advance(minute + 1)
        assertFalse(k.isActive())
    }

    @Test
    fun trip_persistsAcrossInstances() {
        KillSwitch(store, clock).trip()
        clock.advance(10 * minute)
        assertTrue(KillSwitch(store, clock).isActive())
    }

    @Test
    fun setOptedOut_isVisibleAtOnceButNotPersistedUntilAsked() {
        val k = KillSwitch(store, clock)
        k.applyOptedOut(true)
        assertTrue(k.optedOut)
        assertTrue(k.isActive())
        assertFalse(KillSwitch(store, clock).isActive())

        k.persistOptedOut()
        assertTrue(KillSwitch(store, clock).isActive())

        k.applyOptedOut(false)
        assertFalse(k.isActive())
        assertTrue(KillSwitch(store, clock).isActive())
        k.persistOptedOut()
        assertFalse(KillSwitch(store, clock).isActive())
    }

    @Test
    fun optOut_persists() {
        val k = KillSwitch(store, clock)
        k.optedOut = true
        clock.advance(100 * 60 * minute)
        assertTrue(KillSwitch(store, clock).optedOut)
        assertTrue(KillSwitch(store, clock).isActive())
        k.optedOut = false
        assertFalse(KillSwitch(store, clock).isActive())
    }

    @Test
    fun disabledUntilFurtherAwayThanAnyTrip_isTreatedAsExpiredAndCleared() {
        // A wall clock that was far ahead when the trip was stored (or a corrupted value) must not lock the SDK for good.
        store.putLong(KillSwitch.KEY_DISABLED_UNTIL, clock.nowMs() + KillSwitch.DEFAULT_TRIP_MS + 1)
        val k = KillSwitch(store, clock)

        assertFalse(k.isActive())
        assertEquals("the bogus value is cleared", 0L, store.getLong(KillSwitch.KEY_DISABLED_UNTIL, -1L))
    }

    @Test
    fun disabledUntilExactlyOneTripAway_isStillActive() {
        store.putLong(KillSwitch.KEY_DISABLED_UNTIL, clock.nowMs() + KillSwitch.DEFAULT_TRIP_MS)

        assertTrue(KillSwitch(store, clock).isActive())
    }

    @Test
    fun clockJumpingBackwardsAfterATrip_doesNotLockForMoreThanOneTrip() {
        val k = KillSwitch(store, clock)
        k.trip()
        clock.advance(-2 * 3_600_000L)

        assertFalse(k.isActive())
    }
}
