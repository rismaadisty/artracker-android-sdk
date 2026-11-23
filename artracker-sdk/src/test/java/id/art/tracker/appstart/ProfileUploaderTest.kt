package id.art.tracker.appstart

import id.art.tracker.core.KillSwitch
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.InMemoryStore
import id.art.tracker.transport.FlushOutcome
import id.art.tracker.transport.SendResult
import id.art.tracker.transport.Sender
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileUploaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock(1_700_000_000_000L)
    private val killSwitch = KillSwitch(InMemoryStore(), clock)
    private val sent = ArrayList<ByteArray>()

    private fun store() = ProfileStore(File(tmp.root, "appstart"), clock)

    private fun scripted(vararg results: SendResult) = Sender { body ->
        sent += body
        results[minOf(sent.size, results.size) - 1]
    }

    private fun filled(store: ProfileStore, vararg ids: String) = ids.forEach {
        store.write(it, it.toByteArray())
        clock.advance(10)
    }

    @Test
    fun accepted_dropAndTooLarge_deleteTheFile() {
        val store = store()
        filled(store, "a", "b", "c")
        val uploader = ProfileUploader(store, scripted(SendResult.Accepted, SendResult.Drop(400), SendResult.TooLarge), killSwitch)

        assertEquals(FlushOutcome.SENT, uploader.uploadAll())

        assertEquals(3, sent.size)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun filesGoOldestFirst() {
        val store = store()
        filled(store, "first", "second")
        ProfileUploader(store, scripted(SendResult.Accepted), killSwitch).uploadAll()

        assertEquals(listOf("first", "second"), sent.map { String(it) })
    }

    @Test
    fun networkError_marksFailedAndStops() {
        val store = store()
        filled(store, "a", "b")
        val uploader = ProfileUploader(store, scripted(SendResult.NetworkError(IOException("offline"))), killSwitch)

        assertEquals(FlushOutcome.FAILED, uploader.uploadAll())

        assertEquals(1, sent.size)
        val pending = store.pending()
        assertEquals(listOf("a" to 1, "b" to 0), pending.map { it.startId to it.attempts })
    }

    @Test
    fun retryAfter_marksFailedAndStops() {
        val store = store()
        filled(store, "a", "b")
        val uploader = ProfileUploader(store, scripted(SendResult.RetryAfter(60_000)), killSwitch)

        assertEquals(FlushOutcome.FAILED, uploader.uploadAll())

        assertEquals(1, sent.size)
        assertEquals(listOf("a" to 1, "b" to 0), store.pending().map { it.startId to it.attempts })
    }

    @Test
    fun unauthorized_keepsTheFileAndStops() {
        val store = store()
        filled(store, "a", "b")
        val uploader = ProfileUploader(store, scripted(SendResult.Unauthorized), killSwitch)

        uploader.uploadAll()

        assertEquals(1, sent.size)
        assertEquals(listOf("a" to 0, "b" to 0), store.pending().map { it.startId to it.attempts })
        assertTrue("the ingest flusher owns the kill-switch trip", !killSwitch.isActive())
    }

    @Test
    fun killSwitch_sendsNothing() {
        val store = store()
        filled(store, "a")
        killSwitch.optedOut = true
        val uploader = ProfileUploader(store, scripted(SendResult.Accepted), killSwitch)

        assertEquals(FlushOutcome.DISABLED, uploader.uploadAll())

        assertTrue(sent.isEmpty())
        assertEquals(1, store.pending().size)
    }

    @Test
    fun noFiles_isEmpty() {
        assertEquals(FlushOutcome.EMPTY, ProfileUploader(store(), scripted(SendResult.Accepted), killSwitch).uploadAll())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun fifthFailedUpload_deletesTheFile() {
        val store = store()
        filled(store, "a")
        val uploader = ProfileUploader(store, scripted(SendResult.NetworkError(IOException("offline"))), killSwitch)

        repeat(ProfileStore.MAX_ATTEMPTS) { uploader.uploadAll() }

        assertEquals(ProfileStore.MAX_ATTEMPTS, sent.size)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun twoCallers_neverUploadAtTheSameTime() {
        val store = store()
        filled(store, "a", "b", "c", "d")
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val sender = Sender {
            val n = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(n, ::maxOf)
            Thread.sleep(20)
            inFlight.decrementAndGet()
            SendResult.Accepted
        }
        val uploader = ProfileUploader(store, sender, killSwitch)
        val start = CountDownLatch(1)
        val threads = List(2) { Thread { start.await(); uploader.uploadAll() } }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals(1, maxInFlight.get())
        assertTrue(store.pending().isEmpty())
    }
}
