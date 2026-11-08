package id.art.tracker.core

import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.InMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private val minute = 60_000L
    private val timeout = 30 * minute

    private class RecordingListener : SessionManager.Listener {
        val starts = mutableListOf<String>()
        val ends = mutableListOf<Pair<String, Long>>()

        override fun onSessionStart(id: String) {
            starts += id
        }

        override fun onSessionEnd(id: String, durationMs: Long) {
            ends += id to durationMs
        }
    }

    private val clock = FakeClock(1_700_000_000_000L)
    private val store = InMemoryStore()
    private val listener = RecordingListener()

    private fun manager(
        s: InMemoryStore = store,
        l: RecordingListener = listener,
    ) = SessionManager(s, clock, timeout, listener = l)

    @Test
    fun firstCall_startsSession() {
        val m = manager()
        val id = m.currentId()
        assertEquals(listOf(id), listener.starts)
        assertEquals(id, m.currentId())
        assertEquals(1, listener.starts.size)
        assertTrue(id.length <= 128)
        assertEquals('7', id[14])
    }

    @Test
    fun sameIdWithinTimeout() {
        val m = manager()
        m.onForeground()
        val id = m.currentId()
        m.onBackground()
        clock.advance(29 * minute)
        m.onForeground()
        assertEquals(id, m.currentId())
        assertEquals(1, listener.starts.size)
        assertTrue(listener.ends.isEmpty())
    }

    @Test
    fun newIdAfterTimeout() {
        val m = manager()
        m.onForeground()
        val old = m.currentId()
        clock.advance(5 * minute)
        m.onBackground()
        clock.advance(timeout + 1)
        m.onForeground()
        val fresh = m.currentId()
        assertNotEquals(old, fresh)
        assertEquals(listOf(old, fresh), listener.starts)
        assertEquals(listOf(old to 5 * minute), listener.ends)
    }

    @Test
    fun hardCap24h() {
        val m = manager()
        m.onForeground()
        val old = m.currentId()
        clock.advance(86_400_000L + 1)
        val fresh = m.currentId()
        assertNotEquals(old, fresh)
        assertEquals(listOf(old to 86_400_001L), listener.ends)
        assertEquals(listOf(old, fresh), listener.starts)
    }

    @Test
    fun restoredAcrossInstances() {
        val first = manager()
        first.onForeground()
        val id = first.currentId()
        first.onBackground()
        clock.advance(10 * minute)

        val secondListener = RecordingListener()
        val second = manager(l = secondListener)
        second.onForeground()
        assertEquals(id, second.currentId())
        assertTrue(secondListener.starts.isEmpty())
        assertTrue(secondListener.ends.isEmpty())
    }

    @Test
    fun restoredAfterTimeout_endsOldAndStartsNew() {
        val first = manager()
        first.onForeground()
        val id = first.currentId()
        first.onBackground()
        clock.advance(timeout + 1)

        val secondListener = RecordingListener()
        val second = manager(l = secondListener)
        second.onForeground()
        val fresh = second.currentId()
        assertNotEquals(id, fresh)
        assertEquals(listOf(fresh), secondListener.starts)
        assertEquals(listOf(id to 0L), secondListener.ends)
    }

    @Test
    fun rotate_endsAndStarts() {
        val m = manager()
        val old = m.currentId()
        clock.advance(1_000)
        m.rotate()
        val fresh = m.currentId()
        assertNotEquals(old, fresh)
        assertEquals(listOf(old to 1_000L), listener.ends)
        assertEquals(listOf(old, fresh), listener.starts)
    }

    @Test
    fun rotate_withoutSession_justStarts() {
        val m = manager()
        m.rotate()
        assertEquals(1, listener.starts.size)
        assertTrue(listener.ends.isEmpty())
    }

    @Test
    fun listener_canCallCurrentIdFromCallbacks_withoutStartingSpuriousSession() {
        lateinit var m: SessionManager
        val seenInStart = mutableListOf<String>()
        val seenInEnd = mutableListOf<String>()
        val starts = mutableListOf<String>()
        val ends = mutableListOf<String>()
        m =
            SessionManager(
                store,
                clock,
                timeout,
                listener =
                    object : SessionManager.Listener {
                        override fun onSessionStart(id: String) {
                            starts += id
                            seenInStart += m.currentId()
                        }

                        override fun onSessionEnd(id: String, durationMs: Long) {
                            ends += id
                            seenInEnd += m.currentId()
                        }
                    },
            )
        val old = m.currentId()
        clock.advance(1_000)
        m.rotate()
        val fresh = m.currentId()

        assertNotEquals(old, fresh)
        assertEquals(listOf(old, fresh), starts)
        assertEquals(listOf(old), ends)
        assertEquals(listOf(old, fresh), seenInStart)
        assertEquals(listOf(fresh), seenInEnd)
        assertEquals(fresh, m.currentId())
    }

    @Test
    fun hardCap_listenerCallingCurrentId_startsExactlyOneNewSession() {
        lateinit var m: SessionManager
        val starts = mutableListOf<String>()
        val ends = mutableListOf<String>()
        m =
            SessionManager(
                store,
                clock,
                timeout,
                listener =
                    object : SessionManager.Listener {
                        override fun onSessionStart(id: String) {
                            starts += id
                        }

                        override fun onSessionEnd(id: String, durationMs: Long) {
                            ends += id
                            m.currentId()
                        }
                    },
            )
        m.onForeground()
        val old = m.currentId()
        clock.advance(86_400_000L + 1)
        val fresh = m.currentId()
        assertEquals(listOf(old, fresh), starts)
        assertEquals(listOf(old), ends)
    }

    @Test
    fun listener_isNotInvokedWhileHoldingLock() {
        lateinit var m: SessionManager
        val held = mutableListOf<Boolean>()
        m =
            SessionManager(
                store,
                clock,
                timeout,
                listener =
                    object : SessionManager.Listener {
                        override fun onSessionStart(id: String) {
                            held += Thread.holdsLock(m)
                        }

                        override fun onSessionEnd(id: String, durationMs: Long) {
                            held += Thread.holdsLock(m)
                        }
                    },
            )
        m.onForeground()
        m.rotate()
        m.currentId()
        clock.advance(86_400_000L + 1)
        m.currentId()
        m.onBackground()
        clock.advance(timeout + 1)
        m.onForeground()
        assertTrue(held.size >= 6)
        assertTrue(held.none { it })
    }

    @Test
    fun rotate_dispatchesEndThenStartInOrder() {
        val order = mutableListOf<String>()
        val m =
            SessionManager(
                store,
                clock,
                timeout,
                listener =
                    object : SessionManager.Listener {
                        override fun onSessionStart(id: String) {
                            order += "start:$id"
                        }

                        override fun onSessionEnd(id: String, durationMs: Long) {
                            order += "end:$id"
                        }
                    },
            )
        val old = m.currentId()
        m.rotate()
        val fresh = m.currentId()
        assertEquals(listOf("start:$old", "end:$old", "start:$fresh"), order)
    }
}
