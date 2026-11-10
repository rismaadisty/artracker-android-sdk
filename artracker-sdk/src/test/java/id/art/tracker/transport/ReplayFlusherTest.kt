package id.art.tracker.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.core.KillSwitch
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.InMemoryStore
import java.io.IOException
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayFlusherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(1_700_000_000_000L)
    private val dbName = "artracker_replay_flusher_test.db"
    private val killSwitch = KillSwitch(InMemoryStore(), clock)
    private var scheduled = 0
    private val scheduler = BackgroundScheduler { scheduled++ }

    /** Always draws just below 1.0 so full-jitter backoff is deterministic: 2000, 4000, 8000... */
    private val maxJitter = object : Random() {
        override fun nextDouble(): Double = 0.9999
    }

    private lateinit var store: ReplayStore
    private val uploads = mutableListOf<Pair<String, Int>>()
    private val results = ArrayDeque<ReplayResult>()
    private val sender = ReplaySender { u ->
        uploads += u.sessionId to u.seq
        results.removeFirstOrNull() ?: ReplayResult.Done
    }

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        store = ReplayStore(context, clock, dbName = dbName)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
    }

    private fun flusher(queue: ReplayQueue = store, sender: ReplaySender = this.sender) =
        ReplayFlusher(queue, sender, killSwitch, clock, Backoff(random = maxJitter), scheduler)

    private fun seed(session: String = "session-A", count: Int = 1) {
        repeat(count) {
            store.enqueue(
                NewReplayBatch(session, byteArrayOf(1, 2, 3), 1, 1_000, 2_000, false, null, "test"),
            )
            clock.advance(1)
        }
    }

    @Test
    fun done_completesUpload_resetsFailures() {
        seed(count = 2)
        val f = flusher()
        results += ReplayResult.Retry(null)
        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(clock.nowMs() + 2_000, f.pausedUntilMs)

        clock.advance(2_000)
        results += ReplayResult.Done
        assertEquals(FlushOutcome.SENT, f.flushOnce())
        assertEquals("replay_uploaded", f.lastResult)
        assertEquals(1, store.pendingCount()) // one of the two batches was delivered

        // The failure counter was reset by the success: the next retry backs off from the start again.
        results += ReplayResult.Retry(null)
        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(clock.nowMs() + 2_000, f.pausedUntilMs)
    }

    @Test
    fun drop_completesUpload() {
        seed()
        val f = flusher()
        results += ReplayResult.Drop("http_400")

        assertEquals(FlushOutcome.DROPPED, f.flushOnce())
        assertEquals(0, store.pendingCount())
        assertEquals("replay_dropped_http_400", f.lastResult)
    }

    @Test
    fun unauthorized_tripsKillSwitch_releases_returnsDisabled() {
        seed()
        val f = flusher()
        results += ReplayResult.Unauthorized

        assertEquals(FlushOutcome.DISABLED, f.flushOnce())
        assertTrue(killSwitch.isActive())
        assertEquals(1, store.pendingCount())
        assertEquals("replay_unauthorized", f.lastResult)
    }

    @Test
    fun expiredPutSignature_doesNotTripKillSwitch() {
        seed()
        val f = flusher()
        // What ReplayClient returns for a 401/403 on the PUT.
        results += ReplayResult.Retry(0)

        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertFalse(killSwitch.isActive())
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun retry_withServerDelay_pausesUntil_andReleases() {
        seed()
        val f = flusher()
        results += ReplayResult.Retry(7_000)

        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(clock.nowMs() + 7_000, f.pausedUntilMs)
        assertEquals("replay_retry", f.lastResult)
        assertEquals(1, store.pendingCount())

        val before = uploads.size
        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(before, uploads.size)

        clock.advance(7_000)
        assertEquals(FlushOutcome.SENT, f.flushOnce())
    }

    @Test
    fun retry_withoutDelay_usesBackoff() {
        seed()
        val f = flusher()
        results += ReplayResult.Retry(null)
        results += ReplayResult.Retry(null)

        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(clock.nowMs() + 2_000, f.pausedUntilMs)
        clock.advance(2_000)
        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(clock.nowMs() + 4_000, f.pausedUntilMs)
    }

    @Test
    fun networkError_releases_pauses_schedulesBackgroundFlush() {
        seed()
        val f = flusher()
        results += ReplayResult.NetworkError(IOException("offline"))

        assertEquals(FlushOutcome.FAILED, f.flushOnce())
        assertEquals(1, scheduled)
        assertEquals(1, store.pendingCount())
        assertEquals(clock.nowMs() + 2_000, f.pausedUntilMs)
        assertEquals("replay_network_error", f.lastResult)
    }

    @Test
    fun killSwitchActive_claimsNothing() {
        seed()
        killSwitch.trip()
        val f = flusher()

        assertEquals(FlushOutcome.DISABLED, f.flushOnce())
        assertTrue(uploads.isEmpty())
        assertEquals(1, store.pendingCount())
        assertEquals("replay_disabled", f.lastResult)
    }

    @Test
    fun empty_returnsEmptyWithoutSending() {
        assertEquals(FlushOutcome.EMPTY, flusher().flushOnce())
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun flushAll_drainsInSeqOrder_thenEmpty() = runTest {
        seed(count = 3)

        assertEquals(FlushOutcome.EMPTY, flusher().flushAll(maxUploadsPerSecond = 1_000))

        assertEquals(listOf("session-A" to 0, "session-A" to 1, "session-A" to 2), uploads)
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun flushAll_stopsAtThePausingOutcome() = runTest {
        seed(count = 3)
        results += ReplayResult.Done
        results += ReplayResult.Retry(5_000)

        assertEquals(FlushOutcome.PAUSED, flusher().flushAll(maxUploadsPerSecond = 1_000))

        assertEquals(2, uploads.size)
        assertEquals(2, store.pendingCount())
    }

    @Test
    fun screenshotDisabled_dropsTheBatch_clearsQueuedScreenshots_andTellsTheOwner() {
        store.enqueue(NewReplayBatch("s", byteArrayOf(1), 1, 1_000, 2_000, false, null, "test", ReplayFormat.SCREEN_V1))
        store.enqueue(NewReplayBatch("s", byteArrayOf(2), 1, 1_000, 2_000, false, null, "test", ReplayFormat.SCREEN_V1))
        var told = 0
        val f = ReplayFlusher(store, sender, killSwitch, clock, Backoff(random = maxJitter), scheduler, onScreenshotDisabled = { told++ })
        results += ReplayResult.ScreenshotDisabled

        assertEquals(FlushOutcome.DROPPED, f.flushOnce())

        assertEquals("replay_screenshot_disabled", f.lastResult)
        assertEquals(1, told)
        assertEquals(0, store.pendingCount()) // the chunk that was still pending is gone too
    }

    /** Counts claim() calls so the test can tell how many drain rounds the kicker ran. */
    private class CountingQueue(private val inner: ReplayQueue) : ReplayQueue by inner {
        val claims = AtomicInteger()

        override fun claim(): ReplayUpload? {
            claims.incrementAndGet()
            return inner.claim()
        }
    }

    @Test
    fun kicker_runsOneFlushAtATime_andReRunsWhenKickedDuring() {
        seed()
        val queue = CountingQueue(store)
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val blocking = ReplaySender {
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { m -> maxOf(m, now) }
            entered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            concurrent.decrementAndGet()
            ReplayResult.Done
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val kicker = ReplayKicker(CoroutineScope(executor.asCoroutineDispatcher()), kotlin.coroutines.EmptyCoroutineContext, flusher(queue, blocking))

            kicker.kick()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            kicker.kick()
            kicker.kick()
            gate.countDown()

            // First round claims the batch, the one extra round (for both kicks made meanwhile) finds nothing.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (queue.claims.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            Thread.sleep(300)
            assertEquals(2, queue.claims.get())
            assertEquals(1, maxConcurrent.get())
            assertEquals(0, store.pendingCount())
        } finally {
            executor.shutdownNow()
        }
    }
}
