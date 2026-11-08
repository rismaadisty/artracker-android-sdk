package id.art.tracker.telemetry

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashStoreTest {
    private lateinit var root: File
    private lateinit var dir: File

    @Before
    fun setUp() {
        root = File.createTempFile("crashstore", "").also { it.delete(); it.mkdirs() }
        dir = File(root, "artracker/crash")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun write_isAtomic_noTmpLeft() {
        val store = CrashStore(dir)

        assertTrue(store.writeSync("abc", "{\"a\":1}"))

        assertEquals(listOf("abc.json"), dir.list()!!.toList())
        assertEquals("{\"a\":1}", File(dir, "abc.json").readText())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun write_createsMissingDirectory() {
        assertFalse(dir.exists())
        assertTrue(CrashStore(dir).writeSync("x", "{}"))
        assertTrue(File(dir, "x.json").isFile)
    }

    @Test
    fun write_failure_returnsFalse_andDoesNotThrow() {
        // The "directory" is a regular file: nothing can be written below it.
        File(root, "artracker").writeText("in the way")
        val store = CrashStore(dir)

        assertFalse(store.writeSync("x", "{}"))
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun write_rejectsIdsThatEscapeTheDirectory() {
        val store = CrashStore(dir)
        assertFalse(store.writeSync("../evil", "{}"))
        assertFalse(store.writeSync("", "{}"))
        assertFalse(File(root, "artracker/evil.json").exists())
    }

    @Test
    fun pending_listsOnlyCompleteFiles_oldestFirst_andToleratesMissingDir() {
        val store = CrashStore(dir)
        assertTrue(store.pending().isEmpty())
        store.writeSync("0002", "{}")
        store.writeSync("0001", "{}")
        File(dir, "0003.tmp").writeText("half")

        assertEquals(listOf("0001.json", "0002.json"), store.pending().map { it.name })
    }

    @Test
    fun readAndDelete() {
        val store = CrashStore(dir)
        store.writeSync("a", "{\"k\":\"é\"}")
        val f = store.pending().single()
        assertEquals("{\"k\":\"é\"}", store.read(f))

        store.delete(f)
        assertNull(store.read(f))
        assertTrue(store.pending().isEmpty())
        store.delete(f) // already gone: no throw
    }

    @Test
    fun read_ofHugeFile_isNull() {
        dir.mkdirs()
        val f = File(dir, "big.json")
        f.writeBytes(ByteArray(300 * 1024) { 'a'.code.toByte() })
        assertNull(CrashStore(dir).read(f))
    }

    @Test
    fun deleteStaleTemp_onlyRemovesOldTmpFiles() {
        dir.mkdirs()
        val old = File(dir, "old.tmp").apply { writeText("x"); setLastModified(1_000) }
        val fresh = File(dir, "fresh.tmp").apply { writeText("x") }
        val complete = File(dir, "ok.json").apply { writeText("{}"); setLastModified(1_000) }

        CrashStore(dir).deleteStaleTemp(nowMs = System.currentTimeMillis())

        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(complete.exists())
    }
}
