package id.art.tracker.telemetry

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashHandlerTest {
    private lateinit var root: File
    private lateinit var store: CrashStore
    private val originalDefault = Thread.getDefaultUncaughtExceptionHandler()

    private class Recorder : Thread.UncaughtExceptionHandler {
        val calls = ArrayList<Pair<Thread, Throwable>>()
        var onCall: () -> Unit = {}
        override fun uncaughtException(t: Thread, e: Throwable) {
            calls += t to e
            onCall()
        }
    }

    @Before
    fun setUp() {
        root = File.createTempFile("crashhandler", "").also { it.delete(); it.mkdirs() }
        store = CrashStore(File(root, "crash"))
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalDefault)
        root.deleteRecursively()
    }

    @Test
    fun writesFileThenDelegates() {
        val previous = Recorder()
        var filesWhenDelegated = -1
        previous.onCall = { filesWhenDelegated = store.pending().size }
        val handler = CrashHandler(previous, store, { _, e -> "id-1" to "{\"msg\":\"${e.message}\"}" })
        val boom = RuntimeException("boom")

        handler.uncaughtException(Thread.currentThread(), boom)

        assertEquals("{\"msg\":\"boom\"}", File(root, "crash/id-1.json").readText())
        assertEquals("file is on disk before the previous handler runs", 1, filesWhenDelegated)
        assertEquals(1, previous.calls.size)
        assertSame(Thread.currentThread(), previous.calls[0].first)
        assertSame(boom, previous.calls[0].second)
    }

    @Test
    fun previousHandler_calledExactlyOnce_evenWhenWriteFails() {
        val previous = Recorder()
        val throwing = CrashHandler(previous, store, { _, _ -> throw IllegalStateException("builder broke") })
        throwing.uncaughtException(Thread.currentThread(), RuntimeException("a"))
        assertEquals(1, previous.calls.size)

        val oom = CrashHandler(previous, store, { _, _ -> throw OutOfMemoryError() })
        oom.uncaughtException(Thread.currentThread(), RuntimeException("b"))
        assertEquals(2, previous.calls.size)

        // Writing itself fails (the directory is blocked by a file).
        File(root, "blocked").writeText("x")
        val blockedStore = CrashStore(File(root, "blocked/crash"))
        val unwritable = CrashHandler(previous, blockedStore, { _, _ -> "id" to "{}" })
        unwritable.uncaughtException(Thread.currentThread(), RuntimeException("c"))
        assertEquals(3, previous.calls.size)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun previousHandlerThrowing_doesNotPropagate() {
        val previous = Recorder().apply { onCall = { throw IllegalStateException("crashlytics bug") } }
        CrashHandler(previous, store, { _, _ -> "id" to "{}" })
            .uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(1, previous.calls.size)
        assertEquals(1, store.pending().size)
    }

    @Test
    fun reentrantCrash_delegatesImmediately() {
        val previous = Recorder()
        lateinit var handler: CrashHandler
        val inner = RuntimeException("inner")
        var delegatedDuringBuild = -1
        var builds = 0
        handler = CrashHandler(previous, store, { thread, _ ->
            builds++
            if (builds == 1) {
                // A crash while the handler is working: must go straight to the previous handler.
                handler.uncaughtException(thread, inner)
                delegatedDuringBuild = previous.calls.size
            }
            "id-$builds" to "{}"
        })
        val outer = RuntimeException("outer")

        handler.uncaughtException(Thread.currentThread(), outer)

        assertEquals(1, builds)
        assertEquals("inner crash delegated while the outer one was still being written", 1, delegatedDuringBuild)
        assertEquals(listOf<Throwable>(inner, outer), previous.calls.map { it.second })
        assertEquals(listOf("id-1.json"), store.pending().map { it.name })
    }

    @Test
    fun afterAReentrantCrash_nextCrashIsCapturedAgain() {
        val previous = Recorder()
        var n = 0
        val handler = CrashHandler(previous, store, { _, _ -> "id-${++n}" to "{}" })
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(2, store.pending().size)
        assertEquals(2, previous.calls.size)
    }

    @Test
    fun shouldCaptureFalse_writesNothing_butStillDelegates() {
        val previous = Recorder()
        val handler = CrashHandler(previous, store, { _, _ -> "id" to "{}" }, shouldCapture = { false })
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertTrue(store.pending().isEmpty())
        assertEquals(1, previous.calls.size)
    }

    @Test
    fun install_chainsToExistingDefault_andUninstallRestoresIt() {
        val existing = Recorder()
        Thread.setDefaultUncaughtExceptionHandler(existing)

        val handler = CrashHandler.install(store, { _, _ -> "id" to "{}" })
        assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(1, existing.calls.size)

        handler.uninstall()
        assertSame(existing, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun install_replacesALeftoverHandler_insteadOfChainingToIt() {
        val existing = Recorder()
        Thread.setDefaultUncaughtExceptionHandler(existing)
        val first = CrashHandler.install(store, { _, _ -> "first" to "{}" })
        val second = CrashHandler.install(store, { _, _ -> "second" to "{}" })

        second.uncaughtException(Thread.currentThread(), RuntimeException())

        assertEquals("only the newest handler writes", listOf("second.json"), store.pending().map { it.name })
        assertEquals(1, existing.calls.size)
        assertNotSame(first, Thread.getDefaultUncaughtExceptionHandler())
        // The stale one is inert if somebody still calls it.
        first.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(1, store.pending().size)
        assertEquals(2, existing.calls.size)

        second.uninstall()
        assertSame(existing, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun uninstall_whenSomebodyInstalledAfterUs_doesNotClobberThem_andTurnsPassThrough() {
        val existing = Recorder()
        Thread.setDefaultUncaughtExceptionHandler(existing)
        val ours = CrashHandler.install(store, { _, _ -> "id" to "{}" })
        val theirs = Recorder()
        Thread.setDefaultUncaughtExceptionHandler(theirs)

        ours.uninstall()

        assertSame(theirs, Thread.getDefaultUncaughtExceptionHandler())
        ours.uncaughtException(Thread.currentThread(), RuntimeException())
        assertFalse(File(root, "crash/id.json").exists())
        assertEquals(1, existing.calls.size)
    }
}
