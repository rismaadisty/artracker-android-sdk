package id.art.tracker.playground.boot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueueWiperTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String): File = File(dir, name).also {
        it.parentFile!!.mkdirs()
        it.writeText("x")
    }

    @Test
    fun deletesTheSdkQueueFilesAndNothingElse() {
        val db = tmp.newFolder("databases")
        val files = tmp.newFolder("files")
        listOf("artracker.db", "artracker.db-wal", "artracker.db-shm", "artracker.db-journal").forEach { file(db, it) }
        file(files, "artracker/crash/x.json")
        file(db, "other.db")
        file(files, "keep.txt")

        assertEquals(5, QueueWiper.wipe(db, files))

        listOf("artracker.db", "artracker.db-wal", "artracker.db-shm", "artracker.db-journal").forEach {
            assertFalse(it, File(db, it).exists())
        }
        assertFalse(File(files, "artracker").exists())
        assertTrue(File(db, "other.db").exists())
        assertTrue(File(files, "keep.txt").exists())
    }

    @Test
    fun emptyDirectoriesReturnZero() {
        assertEquals(0, QueueWiper.wipe(tmp.newFolder("d"), tmp.newFolder("f")))
    }

    @Test
    fun missingDirectoriesDoNotThrow() {
        assertEquals(0, QueueWiper.wipe(File(tmp.root, "nope-db"), File(tmp.root, "nope-files")))
    }
}
