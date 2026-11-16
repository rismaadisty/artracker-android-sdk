package id.art.tracker.transport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
import java.io.File

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

    private val bodiesDir: File get() = File(context.noBackupFilesDir, "artracker/replay")

    private fun bodyFiles(): List<File> = bodiesDir.listFiles()?.toList().orEmpty()

    private fun batch(
        session: String,
        format: String = ReplayFormat.SCREEN_V1,
        firstTs: Long = 1_000L,
        lastTs: Long = 2_000L,
        body: ByteArray = byteArrayOf(1, 2, 3),
        hasError: Boolean = false,
        userId: String? = "u1",
        hasRageClick: Boolean = false,
    ) = NewReplayBatch(
        sessionId = session,
        gzipBody = body,
        eventCount = 3,
        firstTsMs = firstTs,
        lastTsMs = lastTs,
        hasError = hasError,
        userId = userId,
        environment = "test",
        format = format,
        hasRageClick = hasRageClick,
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

    @Test
    fun body_isAFileUnderNoBackupFiles_notABlob_andCompleteDeletesIt() {
        val s = store()
        s.enqueue(batch("A"))
        assertEquals(1, bodyFiles().size)
        val u = s.claim()!!
        assertEquals(1, bodyFiles().size) // in flight: still on disk
        s.complete(u.id)
        assertEquals(0, bodyFiles().size)
    }

    @Test
    fun aBodyLargerThanTheCursorWindow_roundTrips() {
        val s = store()
        val body = ByteArray(3 * 1024 * 1024) { (it * 13).toByte() }
        s.enqueue(batch("A", body = body))
        assertArrayEquals(body, s.claim()!!.gzipBody)
    }

    @Test
    fun format_roundTrips_andDefaultsToScreenV1() {
        val s = store()
        s.enqueue(batch("A"))
        assertEquals(ReplayFormat.SCREEN_V1, s.claim()!!.format)
        assertEquals(ReplayFormat.SCREEN_V1, NewReplayBatch("A", byteArrayOf(1), 1, 1L, 2L, false, null, "test").format)
    }

    @Test
    fun clearFormat_removesOnlyPendingBatchesOfThatFormat_withTheirFiles() {
        val s = store()
        s.enqueue(batch("A"))
        clock.advance(1)
        s.enqueue(batch("A"))
        clock.advance(1)
        s.enqueue(batch("A", format = "other-format"))
        val inFlight = s.claim()!! // in flight: never cleared
        s.clearFormat(ReplayFormat.SCREEN_V1)
        assertEquals(1, s.pendingCount())
        assertEquals(2, bodyFiles().size)
        assertEquals("other-format", s.claim()!!.format)
        s.complete(inFlight.id)
    }

    @Test
    fun eviction_andClear_andPurge_deleteTheirFiles() {
        val s = store(maxBatches = 2)
        s.enqueue(batch("A")); clock.advance(1)
        s.enqueue(batch("A")); clock.advance(1)
        s.enqueue(batch("A")); clock.advance(1)
        assertEquals(2, bodyFiles().size) // the oldest was evicted with its file
        s.purgeOlderThan(clock.nowMs() - 1)
        assertEquals(1, bodyFiles().size)
        s.clear()
        assertEquals(0, bodyFiles().size)
    }

    @Test
    fun claim_skipsAndRemovesARowWhoseFileIsGone() {
        val s = store()
        s.enqueue(batch("A")); clock.advance(1)
        s.enqueue(batch("B", body = byteArrayOf(9, 9)))
        bodyFiles().minByOrNull { it.name }!!.delete() // ids are time-ordered: this is A's body
        val u = s.claim()!!
        assertEquals("B", u.sessionId)
        assertEquals(0, s.pendingCount())
        s.release(u.id)
        assertEquals(1, s.pendingCount()) // A's row is gone, only B remains
    }

    @Test
    fun resetOrphaned_sweepsFilesNoRowPointsTo() {
        val s = store()
        s.enqueue(batch("A"))
        bodiesDir.mkdirs()
        File(bodiesDir, "stray.gz").writeBytes(byteArrayOf(1))
        s.resetOrphaned()
        assertEquals(1, bodyFiles().size)
        assertTrue(bodyFiles().none { it.name == "stray.gz" })
    }

    @Test
    fun defaultCap_is50Megabytes() {
        assertEquals(50L * 1024 * 1024, ReplayStore.DEFAULT_MAX_BYTES)
    }

    private fun createOldStore(version: Int, rows: (SQLiteDatabase) -> Unit) {
        object : SQLiteOpenHelper(context, dbName, null, version) {
            override fun onCreate(db: SQLiteDatabase) {
                val body = if (version == 1) "body BLOB NOT NULL, " else "file TEXT NOT NULL, format TEXT NOT NULL DEFAULT 'rrweb', "
                db.execSQL(
                    "CREATE TABLE replay_batches (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, seq INTEGER NOT NULL, " +
                        "created_at INTEGER NOT NULL, first_ts INTEGER NOT NULL, last_ts INTEGER NOT NULL, " +
                        "event_count INTEGER NOT NULL, has_error INTEGER NOT NULL, user_id TEXT, environment TEXT NOT NULL, " +
                        body + "size_bytes INTEGER NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, " +
                        "attempts INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL("CREATE INDEX idx_replay_pending ON replay_batches(claimed, created_at, seq)")
                db.execSQL(
                    "CREATE TABLE replay_seq (session_id TEXT PRIMARY KEY, next_seq INTEGER NOT NULL, " +
                        "start_ms INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = Unit
        }.also { h ->
            val db = h.writableDatabase
            rows(db)
            db.insertOrThrow(
                "replay_seq", null,
                ContentValues().apply { put("session_id", "OLD"); put("next_seq", 1); put("start_ms", 1L); put("updated_at", 500L) },
            )
            h.close()
        }
    }

    private fun oldRow(id: String, vararg extra: Pair<String, Any>) = ContentValues().apply {
        put("id", id); put("session_id", "OLD"); put("seq", 0); put("created_at", 500L)
        put("first_ts", 1L); put("last_ts", 2L); put("event_count", 4); put("has_error", 1)
        put("environment", "prod"); put("size_bytes", 3)
        for ((k, v) in extra) when (v) {
            is ByteArray -> put(k, v)
            is Int -> put(k, v)
            else -> put(k, v.toString())
        }
    }

    @Test
    fun upgradeFromV1_discardsTheWireframeBatches_butKeepsTheSessionCounters() {
        createOldStore(1) { db -> db.insertOrThrow("replay_batches", null, oldRow("old-1", "body" to byteArrayOf(1, 2, 3))) }

        val s = store()
        assertEquals(0, s.pendingCount())
        assertNull(s.claim())
        // the session counter survived: the next chunk continues the sequence
        s.enqueue(batch("OLD"))
        assertEquals(1, s.claim()!!.seq)
    }

    @Test
    fun upgradeFromV2_discardsQueuedWireframeBatches_andKeepsScreenshotChunks() {
        bodiesDir.mkdirs()
        File(bodiesDir, "old-wire.gz").writeBytes(byteArrayOf(1, 2, 3))
        File(bodiesDir, "old-shot.gz").writeBytes(byteArrayOf(4, 5, 6))
        createOldStore(2) { db ->
            db.insertOrThrow("replay_batches", null, oldRow("old-wire", "file" to "old-wire.gz", "format" to "rrweb"))
            db.insertOrThrow("replay_batches", null, oldRow("old-shot", "file" to "old-shot.gz", "format" to ReplayFormat.SCREEN_V1))
        }

        val s = store()
        s.resetOrphaned() // what the core does on the first use of the store: the discarded row's file goes
        assertEquals(1, s.pendingCount())
        val u = s.claim()!!
        assertEquals("old-shot", u.id)
        assertEquals(ReplayFormat.SCREEN_V1, u.format)
        assertArrayEquals(byteArrayOf(4, 5, 6), u.gzipBody)
        assertEquals(listOf("old-shot.gz"), bodyFiles().map { it.name })
    }

    @Test
    fun enqueue_roundTripsHasRageClick() {
        val s = store()
        assertTrue(s.enqueue(batch("A", hasRageClick = true)))
        assertTrue(s.enqueue(batch("B")))

        val first = s.claim()!!
        val second = s.claim()!!

        assertEquals("A", first.sessionId)
        assertTrue(first.hasRageClick)
        assertEquals("B", second.sessionId)
        assertFalse(second.hasRageClick)
    }

    @Test
    fun upgradeFromV3_keepsQueuedChunks_withRageClickFalse() {
        bodiesDir.mkdirs()
        File(bodiesDir, "old-3.gz").writeBytes(byteArrayOf(7, 8, 9))
        createOldStore(3) { db ->
            db.insertOrThrow("replay_batches", null, oldRow("old-3", "file" to "old-3.gz", "format" to ReplayFormat.SCREEN_V1))
        }

        val s = store()
        s.resetOrphaned()
        assertEquals(1, s.pendingCount())
        val old = s.claim()!!
        assertEquals("old-3", old.id)
        assertFalse(old.hasRageClick)
        assertTrue("has_error of the queued chunk is kept", old.hasError)
        assertArrayEquals(byteArrayOf(7, 8, 9), old.gzipBody)

        // The session counter survived and a new chunk can carry the flag.
        assertTrue(s.enqueue(batch("OLD", hasRageClick = true)))
        val fresh = s.claim()!!
        assertEquals(1, fresh.seq)
        assertTrue(fresh.hasRageClick)
    }

    @Test
    fun upgradeFromV1_newChunksStoreRageClick() {
        createOldStore(1) { db -> db.insertOrThrow("replay_batches", null, oldRow("old-1", "body" to byteArrayOf(1, 2, 3))) }

        val s = store() // the table is rebuilt with the column: no second ALTER
        assertTrue(s.enqueue(batch("OLD", hasRageClick = true)))

        val u = s.claim()!!
        assertEquals(1, u.seq)
        assertTrue(u.hasRageClick)
    }
}
