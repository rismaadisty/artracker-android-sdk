package id.art.tracker.transport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.testutil.FakeClock
import org.junit.After
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(1_000_000L)
    private val dbName = "artracker_test.db"
    private val opened = mutableListOf<EventStore>()

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(dbName)
    }

    private fun store(maxBytes: Long = 5L * 1024 * 1024, maxRows: Int = 5000): EventStore =
        EventStore(context, clock, maxBytes, maxRows, dbName).also { opened += it }

    private fun item(id: String, priority: Int = 2, createdAt: Long = 1L, payload: String = "{\"id\":\"$id\"}") =
        StoredItem(id, priority, createdAt, payload)

    private fun sized(id: String, bytes: Int, priority: Int = 2, createdAt: Long = 1L) =
        item(id, priority, createdAt, "x".repeat(bytes))

    @Test
    fun claim_ordersByPriorityThenAge() {
        val s = store()
        s.insert(
            listOf(
                item("p2-new", 2, 30),
                item("p0", 0, 50),
                item("p2-old", 2, 10),
                item("p1", 1, 40),
                item("p3", 3, 5),
            ),
        )
        val batch = s.claimBatch()!!
        assertEquals(listOf("p0", "p1", "p2-old", "p2-new", "p3"), batch.items.map { it.id })
    }

    @Test
    fun claim_returnsNullWhenEmpty() {
        assertNull(store().claimBatch())
    }

    @Test
    fun claim_respects50Items() {
        val s = store()
        s.insert((1..60).map { item("i%02d".format(it), 2, it.toLong()) })
        val first = s.claimBatch()!!
        assertEquals(50, first.items.size)
        assertEquals("i01", first.items.first().id)
        val second = s.claimBatch()!!
        assertEquals(10, second.items.size)
        assertEquals("i51", second.items.first().id)
    }

    @Test
    fun claim_respects128KB() {
        val s = store()
        s.insert((1..20).map { sized("i%02d".format(it), 10 * 1024, 2, it.toLong()) })
        val batch = s.claimBatch()!!
        assertEquals(12, batch.items.size)
        assertTrue(batch.items.sumOf { it.sizeBytes } <= 131_072)
    }

    @Test
    fun claim_alwaysTakesFirstItemEvenWhenOversized() {
        val s = store()
        s.insert(listOf(sized("big", 200 * 1024, 2, 1), sized("small", 10, 2, 2)))
        val first = s.claimBatch()!!
        assertEquals(listOf("big"), first.items.map { it.id })
        assertEquals(listOf("small"), s.claimBatch()!!.items.map { it.id })
    }

    @Test
    fun claim_stopsBeforeItemThatWouldOverflowEvenIfLaterOnesFit() {
        val s = store()
        s.insert(listOf(sized("a", 100 * 1024, 2, 1), sized("b", 100 * 1024, 2, 2), sized("c", 10, 2, 3)))
        assertEquals(listOf("a"), s.claimBatch()!!.items.map { it.id })
        assertEquals(listOf("b", "c"), s.claimBatch()!!.items.map { it.id })
    }

    @Test
    fun claim_honoursExplicitLimits() {
        val s = store()
        s.insert((1..10).map { item("i%02d".format(it), 2, it.toLong()) })
        assertEquals(3, s.claimBatch(maxItems = 3)!!.items.size)
    }

    @Test
    fun sizeBytes_isUtf8ByteLength() {
        val payload = "é€😀" // 2 + 3 + 4 bytes
        assertEquals(9, item("u", payload = payload).sizeBytes)
    }

    @Test
    fun claimedItems_notClaimedTwice() {
        val s = store()
        s.insert(listOf(item("a", 2, 1), item("b", 2, 2)))
        val first = s.claimBatch()!!
        assertEquals(2, first.items.size)
        assertNull(s.claimBatch())
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun claim_concurrentClaimersNeverShareAnItem() {
        val s = store()
        s.insert((1..200).map { item("i%03d".format(it), 2, it.toLong()) })
        val pool = Executors.newFixedThreadPool(4)
        val claimed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errors = AtomicInteger()
        val done = CountDownLatch(4)
        repeat(4) {
            pool.execute {
                try {
                    while (true) {
                        val b = s.claimBatch(maxItems = 7) ?: break
                        b.items.forEach { claimed += it.id }
                    }
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(0, errors.get())
        assertEquals(200, claimed.size)
        assertEquals(200, claimed.toSet().size)
    }

    @Test
    fun release_makesItemsClaimableAndIncrementsAttempts() {
        val s = store()
        s.insert(listOf(item("a", 2, 1)))
        val b1 = s.claimBatch()!!
        assertEquals(0, s.pendingCount())
        s.release(b1.id)
        assertEquals(1, s.pendingCount())
        assertEquals(1, attemptsOf("a"))
        val b2 = s.claimBatch()!!
        assertEquals(listOf("a"), b2.items.map { it.id })
        s.release(b2.id)
        assertEquals(2, attemptsOf("a"))
    }

    @Test
    fun complete_deletes() {
        val s = store()
        s.insert(listOf(item("a", 2, 1), item("b", 2, 2)))
        val b = s.claimBatch(maxItems = 1)!!
        s.complete(b.id)
        assertEquals(1, rowCount())
        assertEquals(listOf("b"), s.claimBatch()!!.items.map { it.id })
    }

    @Test
    fun duplicateId_ignored() {
        val s = store()
        assertTrue(s.insert(listOf(item("a", 2, 1, "first"))))
        // A duplicate is ignored, which still counts as stored: the row exists.
        assertTrue(s.insert(listOf(item("a", 2, 2, "second"), item("a", 0, 3, "third"))))
        assertEquals(1, rowCount())
        val got = s.claimBatch()!!.items.single()
        assertEquals("first", got.payloadJson)
        assertEquals(2, got.priority)
    }

    @Test
    fun insert_persistsAcrossInstances() {
        store().insert(listOf(item("a", 1, 7, "{\"k\":\"é\"}")))
        val got = store().claimBatch()!!.items.single()
        assertEquals(StoredItem("a", 1, 7, "{\"k\":\"é\"}"), got)
    }

    @Test
    fun eviction_dropsReplayFirstAndNeverPriority0() {
        val s = store(maxRows = 5)
        s.insert(
            listOf(
                item("c1", 0, 1), item("c2", 0, 2), item("c3", 0, 3),
                item("r1", 3, 4), item("r2", 3, 5), item("r3", 3, 6),
            ),
        )
        s.insert(listOf(item("e1", 2, 7)))
        assertEquals(5, rowCount())
        assertEquals(setOf("c1", "c2", "c3"), idsWithPriority(0))
        // oldest replay chunks went first; the newest one and the new event survive
        assertEquals(setOf("r3"), idsWithPriority(3))
        assertEquals(setOf("e1"), idsWithPriority(2))
    }

    @Test
    fun eviction_thenPriority2_thenPriority1() {
        val s = store(maxRows = 4)
        s.insert(listOf(item("c", 0, 1), item("l1", 1, 2), item("e1", 2, 3), item("e2", 2, 4)))
        s.insert(listOf(item("x", 1, 5)))
        assertEquals(4, rowCount())
        assertEquals(setOf("e2"), idsWithPriority(2))
        s.insert(listOf(item("y", 1, 6), item("z", 1, 7)))
        assertEquals(4, rowCount())
        assertEquals(emptySet<String>(), idsWithPriority(2))
        assertEquals(setOf("c"), idsWithPriority(0))
        assertEquals(setOf("x", "y", "z"), idsWithPriority(1))
    }

    @Test
    fun eviction_neverDropsPriority0EvenWhenOverLimit() {
        val s = store(maxRows = 3)
        s.insert((1..6).map { item("c$it", 0, it.toLong()) })
        assertEquals(6, rowCount())
        assertEquals(6, idsWithPriority(0).size)
    }

    @Test
    fun eviction_byBytes() {
        val s = store(maxBytes = 1000)
        s.insert(listOf(sized("c", 400, 0, 1), sized("r1", 300, 3, 2), sized("r2", 300, 3, 3)))
        assertEquals(3, rowCount())
        s.insert(listOf(sized("e", 300, 2, 4)))
        assertEquals(setOf("c", "r2", "e"), allIds().toSet())
    }

    @Test
    fun eviction_doesNotTouchClaimedItems() {
        val s = store(maxRows = 2)
        s.insert(listOf(item("r1", 3, 1), item("r2", 3, 2)))
        val batch = s.claimBatch(maxItems = 1)!!
        assertEquals("r1", batch.items.single().id)
        s.insert(listOf(item("e", 2, 3)))
        assertEquals(setOf("r1", "e"), allIds().toSet())
        s.complete(batch.id)
        assertEquals(listOf("e"), allIds())
    }

    @Test
    fun orphanedBatches_resetOnStart() {
        val s1 = store()
        s1.insert(listOf(item("a", 2, 1), item("b", 2, 2)))
        assertNotNull(s1.claimBatch())
        assertEquals(0, s1.pendingCount())

        val s2 = store()
        s2.resetOrphanedBatches()
        assertEquals(2, s2.pendingCount())
        assertEquals(2, s2.claimBatch()!!.items.size)
    }

    @Test
    fun purgeOlderThan_keepsPriority0() {
        val s = store()
        s.insert(
            listOf(
                item("old0", 0, 10), item("old1", 1, 10), item("old2", 2, 20), item("old3", 3, 30),
                item("edge", 2, 100), item("new", 2, 200),
            ),
        )
        s.purgeOlderThan(100)
        assertEquals(setOf("old0", "edge", "new"), allIds().toSet())
    }

    @Test
    fun clear_deletesEverythingIncludingInFlightAndPriority0() {
        val s = store()
        s.insert(listOf(item("crash", 0, 1), item("err", 1, 2), item("evt", 2, 3), item("chunk", 3, 4)))
        assertNotNull(s.claimBatch(maxItems = 2)) // two rows are now in flight
        assertEquals(2, s.pendingCount())

        s.clear()

        assertEquals(0, s.pendingCount())
        assertTrue(allIds().isEmpty())
        assertNull(s.claimBatch())
        // Still usable afterwards.
        s.insert(listOf(item("after", 2, 5)))
        assertEquals(listOf("after"), allIds())
    }

    @Test
    fun insert_swallowsSqliteFailureAndReadsDegradeGracefully() {
        val s = store()
        s.insert(listOf(item("a", 2, 1)))
        context.getDatabasePath(dbName).path.let { path ->
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE items")
            }
        }
        assertFalse(s.insert(listOf(item("b", 2, 2))))
        assertNull(s.claimBatch())
        assertEquals(0, s.pendingCount())
        s.complete("nope")
        s.release("nope")
        s.resetOrphanedBatches()
        s.purgeOlderThan(1)
    }

    // region held items (buffer-mode performance samples)

    private fun held(id: String, sessionId: String, createdAt: Long, priority: Int = 2) =
        StoredItem(id, priority, createdAt, "{\"id\":\"$id\"}", held = true, sessionId = sessionId)

    private fun heldIds(): Set<String> = db().use { d ->
        d.rawQuery("SELECT id FROM items WHERE held = 1", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    @Test
    fun held_isNeitherClaimedNorCounted_untilPromoted() {
        val s = store()
        s.insert(listOf(held("h1", "s1", clock.nowMs()), item("ready", 2, 5)))
        assertEquals(1, s.pendingCount())
        assertEquals(listOf("ready"), s.claimBatch()!!.items.map { it.id })
        assertNull("the held row stays put", s.claimBatch())

        assertEquals(1, s.promoteHeld("s1"))
        assertEquals(1, s.pendingCount())
        assertEquals(listOf("h1"), s.claimBatch()!!.items.map { it.id })
    }

    @Test
    fun held_trimmedToTheLast60Seconds() {
        val s = store()
        val now = clock.nowMs()
        s.insert(listOf(held("old", "s1", now), held("mid", "s1", now + 30_000)))
        s.insert(listOf(held("new", "s1", now + 61_000)))
        assertEquals("old is more than 60 s before the newest", setOf("mid", "new"), heldIds())
        s.insert(listOf(held("edge", "s1", now + 121_000)))
        assertEquals("exactly 60 s before the newest is kept", setOf("new", "edge"), heldIds())
    }

    @Test
    fun held_cappedAtSevenPerSession_oldestFirst() {
        val s = store()
        val now = clock.nowMs()
        s.insert((1..10).map { held("h%02d".format(it), "s1", now + it * 1_000L) })
        assertEquals((4..10).map { "h%02d".format(it) }.toSet(), heldIds())
    }

    @Test
    fun trim_leavesOtherSessionsAndEarlierProcessRowsAlone() {
        val now = clock.nowMs()
        val first = store()
        first.insert(listOf(held("prev1", "s0", now - 500_000), held("prev2", "s0", now - 400_000)))
        first.close()
        clock.advance(10_000)
        val second = store()
        val t = clock.nowMs()
        second.insert(listOf(held("other", "s2", t)))
        second.insert((1..9).map { held("n%d".format(it), "s1", t + it * 1_000L) })
        val kept = heldIds()
        assertTrue("an earlier process's rows wait for the launch decision", kept.containsAll(setOf("prev1", "prev2")))
        assertTrue("another session's rows are not trimmed", "other" in kept)
        assertEquals("only s1's own rows were capped", 7, kept.count { it.startsWith("n") })
    }

    @Test
    fun promoteHeldBefore_promotesAcrossSessions() {
        val s = store()
        val now = clock.nowMs()
        s.insert(listOf(held("a", "s1", now - 10), held("b", "s2", now - 5), held("c", "s1", now + 5)))
        assertEquals(2, s.promoteHeldBefore(now))
        assertEquals(setOf("c"), heldIds())
        assertEquals(2, s.pendingCount())
    }

    @Test
    fun dropHeld_andDropHeldBefore_deleteRows() {
        val s = store()
        val now = clock.nowMs()
        s.insert(listOf(held("a", "s1", now), held("b", "s2", now), held("c", "s2", now - 5), item("ready", 2, 5)))
        assertEquals(1, s.dropHeld("s1"))
        assertEquals(1, s.dropHeldBefore(now))
        assertEquals(setOf("b"), heldIds())
        assertEquals("a ready item is never touched", 1, s.pendingCount())
        assertEquals(0, s.dropHeld("nobody"))
        assertEquals(0, s.promoteHeld("nobody"))
    }

    @Test
    fun eviction_dropsHeldRowsBeforeAnyReadyItem() {
        val s = store(maxRows = 3)
        val now = clock.nowMs()
        s.insert(listOf(item("e1", 2, now), item("e2", 2, now + 1), item("err", 1, now + 2)))
        s.insert(listOf(held("h", "s1", now + 3)))
        assertEquals("the held row went, not the oldest event", setOf("e1", "e2", "err"), allIds().toSet())
    }

    @Test
    fun upgrade_fromVersion1_keepsRowsAsReadyToSend() {
        context.getDatabasePath(dbName).parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { d ->
            d.execSQL(
                "CREATE TABLE items (id TEXT PRIMARY KEY, priority INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                    "size_bytes INTEGER NOT NULL, payload TEXT NOT NULL, batch_id TEXT, " +
                    "attempts INTEGER NOT NULL DEFAULT 0)",
            )
            d.execSQL("INSERT INTO items VALUES ('old', 2, 1, 2, '{}', NULL, 0)")
            d.version = 1
        }
        val s = store()
        assertEquals("a row from before the upgrade is not held", 1, s.pendingCount())
        s.insert(listOf(held("h", "s1", clock.nowMs())))
        assertEquals(setOf("h"), heldIds())
        assertEquals(listOf("old"), s.claimBatch()!!.items.map { it.id })
    }

    // endregion

    private fun db(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)

    private fun rowCount(): Int = db().use { d ->
        d.rawQuery("SELECT COUNT(*) FROM items", null).use { c -> c.moveToFirst(); c.getInt(0) }
    }

    private fun attemptsOf(id: String): Int = db().use { d ->
        d.rawQuery("SELECT attempts FROM items WHERE id = ?", arrayOf(id)).use { c -> c.moveToFirst(); c.getInt(0) }
    }

    private fun allIds(): List<String> = db().use { d ->
        d.rawQuery("SELECT id FROM items ORDER BY created_at", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    private fun idsWithPriority(p: Int): Set<String> = db().use { d ->
        d.rawQuery("SELECT id FROM items WHERE priority = ?", arrayOf(p.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }
}
