package id.art.tracker.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.testutil.FakeClock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(1_000_000L)
    private val dbName = "artracker_replay_test.db"
    private val opened = mutableListOf<ReplayStore>()

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(dbName)
    }

    private fun store(
        maxBytes: Long = 20L * 1024 * 1024,
        maxBatches: Int = 200,
        maxAttempts: Int = 8,
    ): ReplayStore = ReplayStore(context, clock, maxBytes, maxBatches, maxAttempts, dbName).also { opened += it }

    private fun batch(
        session: String,
        firstTs: Long = 1_000L,
        lastTs: Long = 2_000L,
        body: ByteArray = byteArrayOf(1, 2, 3),
        hasError: Boolean = false,
        userId: String? = "u1",
    ) = NewReplayBatch(
        sessionId = session,
        gzipBody = body,
        eventCount = 3,
        firstTsMs = firstTs,
        lastTsMs = lastTs,
        hasError = hasError,
        userId = userId,
        environment = "test",
    )

    @Test
    fun enqueue_assignsSequentialSeqPerSession() {
        val s = store()
        assertTrue(s.enqueue(batch("A")))
        clock.advance(1)
        assertTrue(s.enqueue(batch("B")))
        clock.advance(1)
        assertTrue(s.enqueue(batch("A")))

        // Oldest enqueue first: (A,0) then (B,0) then (A,1). Seq counts per session, not globally.
        val claimed = (1..3).map { s.claim()!! }
        assertEquals(listOf("A" to 0, "B" to 0, "A" to 1), claimed.map { it.sessionId to it.seq })
    }

    @Test
    fun seq_continuesAfterReopen_andAfterComplete() {
        val first = store()
        first.enqueue(batch("A"))
        val u = first.claim()!!
        assertEquals(0, u.seq)
        first.complete(u.id)
        first.close()

        val reopened = store()
        reopened.enqueue(batch("A"))
        assertEquals(1, reopened.claim()!!.seq)
    }

    @Test
    fun startMs_isFirstBatchFirstTs_forEveryBatchOfTheSession() {
        val s = store()
        s.enqueue(batch("A", firstTs = 1_000, lastTs = 2_000))
        s.enqueue(batch("A", firstTs = 5_000, lastTs = 9_000))

        val one = s.claim()!!
        val two = s.claim()!!
        assertEquals(1_000L, one.startMs)
        assertEquals(1_000L, two.startMs)
        assertEquals(1_000L, one.durationMs)
        assertEquals(8_000L, two.durationMs)
    }

    @Test
    fun claim_hidesClaimedRows_release_returnsThem() {
        val s = store()
        s.enqueue(batch("A"))
        val first = s.claim()!!
        assertNull(s.claim())
        assertEquals(0, s.pendingCount())

        s.release(first.id)
        assertEquals(1, s.pendingCount())
        val again = s.claim()!!
        assertEquals(first.id, again.id)
        assertEquals(first.seq, again.seq)
    }

    @Test
    fun claim_dropsRowsAtMaxAttempts() {
        val s = store(maxAttempts = 2)
        s.enqueue(batch("A"))
        repeat(2) { s.release(s.claim()!!.id) }

        assertNull(s.claim())
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun enqueue_evictsOldestUnclaimed_whenOverMaxBatches() {
        val s = store(maxBatches = 2)
        s.enqueue(batch("A", body = byteArrayOf(1)))
        clock.advance(1)
        s.enqueue(batch("B", body = byteArrayOf(2)))
        clock.advance(1)
        s.enqueue(batch("C", body = byteArrayOf(3)))

        assertEquals(2, s.pendingCount())
        assertEquals("B", s.claim()!!.sessionId)
    }

    @Test
    fun enqueue_neverEvictsInFlightRow() {
        val s = store(maxBatches = 2)
        s.enqueue(batch("A", body = byteArrayOf(1)))
        val inFlight = s.claim()!!
        clock.advance(1)
        s.enqueue(batch("B", body = byteArrayOf(2)))
        clock.advance(1)
        s.enqueue(batch("C", body = byteArrayOf(3)))
        clock.advance(1)
        s.enqueue(batch("D", body = byteArrayOf(4)))

        // The in-flight row stays and counts against the cap, so only one unclaimed row is left: the newest.
        assertEquals(1, s.pendingCount())
        assertEquals("D", s.claim()!!.sessionId)
        // It is still there to be settled.
        s.release(inFlight.id)
        assertEquals("A", s.claim()!!.sessionId)
    }

    @Test
    fun enqueue_evictsOldestUnclaimed_whenOverMaxBytes() {
        val s = store(maxBytes = 25)
        s.enqueue(batch("A", body = ByteArray(10)))
        clock.advance(1)
        s.enqueue(batch("B", body = ByteArray(10)))
        clock.advance(1)
        s.enqueue(batch("C", body = ByteArray(10)))

        assertEquals(2, s.pendingCount())
        assertEquals("B", s.claim()!!.sessionId)
        assertEquals("C", s.claim()!!.sessionId)
        assertNull(s.claim())
    }

    @Test
    fun resetOrphaned_makesClaimedPendingAgain() {
        val s = store()
        s.enqueue(batch("A"))
        assertNotNull(s.claim())
        assertEquals(0, s.pendingCount())

        s.resetOrphaned()

        assertEquals(1, s.pendingCount())
        assertNotNull(s.claim())
    }

    @Test
    fun clear_removesBatchesButKeepsSeqCounters() {
        val s = store()
        s.enqueue(batch("A"))
        s.enqueue(batch("A"))
        s.clear()
        assertEquals(0, s.pendingCount())
        assertNull(s.claim())

        s.enqueue(batch("A"))
        assertEquals(2, s.claim()!!.seq)
    }

    @Test
    fun purgeOlderThan_removesOldBatchesAndOldSeqRows() {
        val s = store()
        s.enqueue(batch("OLD"))
        val old = s.claim()!!
        s.complete(old.id) // leaves only the seq counter behind
        s.enqueue(batch("OLD2"))
        clock.advance(10_000)
        s.enqueue(batch("NEW"))

        s.purgeOlderThan(clock.nowMs() - 5_000)

        // The old batch is gone, the new one stays.
        assertEquals(1, s.pendingCount())
        assertEquals("NEW", s.claim()!!.sessionId)
        // The old session's counter was purged too, so it starts over.
        s.enqueue(batch("OLD"))
        assertEquals(0, s.claim()!!.seq)
    }

    @Test
    fun body_roundTripsByteForByte() {
        val s = store()
        val body = ByteArray(4096) { (it * 31).toByte() }
        s.enqueue(batch("A", body = body, hasError = true, userId = null))

        val u = s.claim()!!
        assertArrayEquals(body, u.gzipBody)
        assertEquals(3, u.eventCount)
        assertTrue(u.hasError)
        assertNull(u.userId)
        assertEquals("test", u.environment)
        assertFalse(u.id.isEmpty())
    }
}
