package id.art.tracker.appstart

import id.art.tracker.testutil.FakeClock
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock(1_700_000_000_000L)
    private val dir get() = File(tmp.root, "artracker/appstart")
    private fun store() = ProfileStore(dir, clock)
    private val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)

    @Test
    fun writeThenPending_returnsItWithAttemptsZero() {
        val store = store()
        assertTrue(store.write("start-1", bytes))

        val pending = store.pending().single()
        assertEquals("start-1", pending.startId)
        assertEquals(0, pending.attempts)
        assertEquals("start-1.a0.gz", pending.file.name)
        assertArrayEquals(bytes, pending.file.readBytes())
        assertTrue("no temp file is left", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aFourthWrite_evictsTheOldest() {
        // Review Focus 3: many profiled launches while offline.
        val store = store()
        listOf("s1", "s2", "s3", "s4").forEach {
            assertTrue(store.write(it, bytes))
            clock.advance(1_000)
        }

        assertEquals(listOf("s2", "s3", "s4"), store.pending().map { it.startId })
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun pendingIsOldestFirst() {
        val store = store()
        store.write("b", bytes)
        clock.advance(10)
        store.write("a", bytes)

        assertEquals(listOf("b", "a"), store.pending().map { it.startId })
    }

    @Test
    fun filesOlderThan3Days_areDeletedOnPending() {
        // Review Focus 3: offline for days.
        val store = store()
        store.write("old", bytes)
        clock.advance(ProfileStore.MAX_AGE_MS)
        assertEquals("exactly 3 days is still kept", listOf("old"), store.pending().map { it.startId })

        clock.advance(1)
        assertTrue(store.pending().isEmpty())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun fifthFailure_deletesTheFile() {
        val store = store()
        store.write("s", bytes)
        repeat(ProfileStore.MAX_ATTEMPTS - 1) { n ->
            store.markFailed(store.pending().single())
            assertEquals(n + 1, store.pending().single().attempts)
        }
        assertEquals("s.a4.gz", store.pending().single().file.name)

        store.markFailed(store.pending().single())

        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun markFailed_keepsTheAgeOfTheFile() {
        val store = store()
        store.write("s", bytes)
        clock.advance(ProfileStore.MAX_AGE_MS - 10)
        store.markFailed(store.pending().single())
        clock.advance(11)

        assertTrue("retrying must not extend the 3 days", store.pending().isEmpty())
    }

    @Test
    fun deleteRemovesTheFile() {
        val store = store()
        store.write("s", bytes)
        store.delete(store.pending().single())

        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun aStartIdWithASlash_isRejected() {
        val store = store()
        assertFalse(store.write("../evil", bytes))
        assertFalse(store.write("a/b", bytes))
        assertFalse(store.write("a\\b", bytes))
        assertFalse(store.write("", bytes))
        assertFalse(store.write("..", bytes))

        assertTrue(store.pending().isEmpty())
        assertFalse(File(tmp.root, "evil.a0.gz").exists())
    }

    @Test
    fun aStaleTempFile_isDeleted_aFreshOneIsKept() {
        val store = store()
        store.write("s", bytes)
        val stale = File(dir, "stale.tmp").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(clock.nowMs() - 3_600_000) }
        val fresh = File(dir, "fresh.tmp").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(clock.nowMs()) }

        assertEquals(listOf("s"), store.pending().map { it.startId })
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun unrelatedFiles_areIgnoredAndKept() {
        val store = store()
        store.write("s", bytes)
        val other = File(dir, "notes.txt").also { it.writeText("x") }

        assertEquals(1, store.pending().size)
        assertTrue(other.exists())
    }

    @Test
    fun aMissingDirectory_meansNothingPending() {
        assertTrue(store().pending().isEmpty())
    }

    @Test
    fun clear_deletesProfilesAndTempFiles_butNotOtherFiles() {
        val store = store()
        store.write("a", bytes)
        store.write("b", bytes)
        val tmpFile = File(dir, "x.tmp").also { it.writeBytes(byteArrayOf(1)) }
        val other = File(dir, "notes.txt").also { it.writeText("x") }

        store.clear()

        assertTrue(store.pending().isEmpty())
        assertFalse(tmpFile.exists())
        assertTrue(other.exists())
    }

    @Test
    fun hasFiles_reflectsTheDirectory() {
        val store = store()
        assertFalse(store.hasFiles())
        store.write("s", bytes)
        assertTrue(store.hasFiles())
    }
}
