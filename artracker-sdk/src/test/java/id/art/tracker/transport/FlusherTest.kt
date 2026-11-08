package id.art.tracker.transport

import id.art.tracker.core.KillSwitch
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.FakeQueue
import id.art.tracker.testutil.FaultyQueue
import id.art.tracker.testutil.InMemoryStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlusherTest {
    private val clock = FakeClock(1_700_000_000_000L)
    private val queue = FakeQueue()
    private val killSwitch = KillSwitch(InMemoryStore(), clock)
    private var scheduled = 0
    private val scheduler = BackgroundScheduler { scheduled++ }

    /** Always draws just below 1.0 so full-jitter backoff is deterministic: 2000, 4000, 8000... */
    private val maxJitter = object : Random() {
        override fun nextDouble(): Double = 0.9999
    }

    private val requests = ArrayList<List<String>>()
    private val results = ArrayDeque<SendResult>()
    private var defaultResult: SendResult = SendResult.Accepted
    private var sendRule: ((List<String>) -> SendResult)? = null

    private val sender = Sender { body ->
        val ids = idsOf(body)
        requests += ids
        sendRule?.invoke(ids) ?: results.removeFirstOrNull() ?: defaultResult
    }

    private var encoderContext: () -> Map<String, Any?> = { mapOf("environment" to "test") }
    private val encoder = PayloadEncoder { encoderContext() }

    private fun flusher(q: ItemQueue = queue) = Flusher(q, encoder, sender, killSwitch, clock, Backoff(random = maxJitter), scheduler)

    private fun idsOf(body: ByteArray): List<String> {
        val json = GZIPInputStream(ByteArrayInputStream(body)).use { String(it.readBytes(), Charsets.UTF_8) }
        val items = JSONObject(json).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("id") }
    }

    private fun seed(count: Int, priority: Int = 2) {
        queue.insert(
            (0 until count).map {
                StoredItem("id-${priority}-$it", priority, 1_000L + it, "{\"id\":\"id-${priority}-$it\"}")
            },
        )
    }

    @Test
    fun empty_returnsEmptyWithoutSending() {
        assertEquals(FlushOutcome.EMPTY, flusher().flushOnce())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun killSwitchActive_disabledWithoutClaim() {
        seed(3)
        killSwitch.trip()
        assertEquals(FlushOutcome.DISABLED, flusher().flushOnce())
        assertTrue(requests.isEmpty())
        assertEquals(3, queue.pendingCount())
        assertEquals(0, queue.inFlightCount())
    }

    @Test
    fun accepted_completesBatchAndResetsFailures() {
        seed(3)
        val f = flusher()
        results += SendResult.NetworkError(IOException("x"))
        assertEquals(FlushOutcome.FAILED, f.flushOnce())
        assertEquals(clock.now + 2_000, f.pausedUntilMs)
        clock.advance(2_000)
        assertEquals(FlushOutcome.SENT, f.flushOnce())
        assertEquals(0, queue.pendingCount())
        assertEquals(0, queue.inFlightCount())
        assertEquals("accepted", f.lastResult)
        // Failure counter was reset: the next failure backs off from attempt 0 again (2000, not 4000).
        seed(1, priority = 1)
        results += SendResult.NetworkError(IOException("y"))
        f.flushOnce()
        assertEquals(clock.now + 2_000, f.pausedUntilMs)
    }

    @Test
    fun drop_completesAndDiscards() {
        seed(2)
        results += SendResult.Drop(400)
        val f = flusher()
        assertEquals(FlushOutcome.DROPPED, f.flushOnce())
        assertEquals(0, queue.pendingCount())
        assertEquals(0, queue.inFlightCount())
        assertEquals("http_400_dropped", f.lastResult)
    }

    @Test
    fun unauthorized_releasesAndTripsKillSwitch() {
        seed(2)
        results += SendResult.Unauthorized
        val f = flusher()
        assertEquals(FlushOutcome.DISABLED, f.flushOnce())
        assertEquals(2, queue.pendingCount())
        assertTrue(killSwitch.isActive())
        assertEquals("unauthorized", f.lastResult)
        // Subsequent call does not even claim.
        assertEquals(FlushOutcome.DISABLED, f.flushOnce())
        assertEquals(1, requests.size)
    }

    @Test
    fun tooLarge_singleItem_isDropped() {
        seed(1)
        results += SendResult.TooLarge
        val f = flusher()
        assertEquals(FlushOutcome.DROPPED, f.flushOnce())
        assertEquals(0, queue.pendingCount())
        assertEquals(0, queue.inFlightCount())
        assertEquals("too_large_dropped", f.lastResult)
    }

    @Test
    fun tooLarge_multipleItems_releasesAndHalvesNextClaim() {
        seed(8)
        results += SendResult.TooLarge
        val f = flusher()
        assertEquals(FlushOutcome.FAILED, f.flushOnce())
        assertEquals(0L, f.pausedUntilMs)
        assertEquals(0, scheduled)
        assertEquals(8, queue.pendingCount())
        assertEquals(FlushOutcome.SENT, f.flushOnce())
        assertEquals(listOf(8, 4), requests.map { it.size })
    }

    @Test
    fun tooLarge_splitsUntilSent() {
        seed(5)
        sendRule = { ids -> if (ids.size > 2) SendResult.TooLarge else SendResult.Accepted }
        val f = flusher()
        var guard = 0
        while (queue.pendingCount() > 0 && guard++ < 20) f.flushOnce()
        assertEquals(0, queue.pendingCount())
        assertTrue("requests=${requests.size}", requests.size >= 3)
        assertEquals(5, requests.filter { it.size <= 2 }.sumOf { it.size })
        assertEquals(5, requests.flatten().distinct().size)
    }

    @Test
    fun flushAll_keepsSplittingAfterTooLarge() = runTest {
        seed(5)
        sendRule = { ids -> if (ids.size > 2) SendResult.TooLarge else SendResult.Accepted }
        assertEquals(FlushOutcome.EMPTY, flusher().flushAll())
        assertEquals(0, queue.pendingCount())
        assertEquals(listOf(5, 2, 2, 1), requests.map { it.size })
    }

    @Test
    fun retryAfter_pausesForServerDelay() {
        seed(2)
        results += SendResult.RetryAfter(30_000)
        val f = flusher()
        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(clock.now + 30_000, f.pausedUntilMs)
        assertEquals(2, queue.pendingCount())
        // Still paused: no new request.
        clock.advance(29_999)
        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(1, requests.size)
        clock.advance(1)
        assertEquals(FlushOutcome.SENT, f.flushOnce())
    }

    @Test
    fun retryAfter_withoutDelay_usesBackoff() {
        seed(2)
        results += SendResult.RetryAfter(null)
        val f = flusher()
        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(clock.now + 2_000, f.pausedUntilMs)
    }

    @Test
    fun networkError_releasesPausesAndSchedulesBackground() {
        seed(2)
        results += SendResult.NetworkError(IOException("offline"))
        val f = flusher()
        assertEquals(FlushOutcome.FAILED, f.flushOnce())
        assertEquals(2, queue.pendingCount())
        assertEquals(clock.now + 2_000, f.pausedUntilMs)
        assertEquals(1, scheduled)
        assertEquals("network_error", f.lastResult)
    }

    @Test
    fun consecutiveFailures_growBackoff() {
        seed(2)
        results += SendResult.NetworkError(IOException("1"))
        results += SendResult.NetworkError(IOException("2"))
        val f = flusher()
        f.flushOnce()
        assertEquals(clock.now + 2_000, f.pausedUntilMs)
        clock.advance(2_000)
        f.flushOnce()
        assertEquals(clock.now + 4_000, f.pausedUntilMs)
        assertEquals(2, scheduled)
    }

    @Test
    fun paused_returnsPausedWithoutClaim() {
        seed(2)
        results += SendResult.NetworkError(IOException("x"))
        val f = flusher()
        f.flushOnce()
        assertEquals(FlushOutcome.PAUSED, f.flushOnce())
        assertEquals(1, requests.size)
        assertEquals(0, queue.inFlightCount())
    }

    @Test
    fun ids_stableAcrossRetry() {
        seed(4)
        results += SendResult.NetworkError(IOException("x"))
        val f = flusher()
        f.flushOnce()
        clock.advance(2_000)
        f.flushOnce()
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
    }

    @Test
    fun encodeFailure_doesNotCrash_dropsPoisonBatch() {
        seed(2)
        encoderContext = { throw IllegalStateException("boom") }
        val f = flusher()
        assertEquals(FlushOutcome.DROPPED, f.flushOnce())
        assertTrue(requests.isEmpty())
        assertEquals(0, queue.pendingCount())
        assertEquals(0, queue.inFlightCount())
        assertEquals("encode_failed_dropped", f.lastResult)
    }

    @Test
    fun senderThrowing_isTreatedAsNetworkError() {
        seed(2)
        val f = Flusher(
            queue, encoder, Sender { throw IllegalStateException("bug") }, killSwitch, clock,
            Backoff(random = maxJitter), scheduler,
        )
        assertEquals(FlushOutcome.FAILED, f.flushOnce())
        assertEquals(2, queue.pendingCount())
        assertEquals(0, queue.inFlightCount())
        assertEquals(1, scheduled)
    }

    @Test
    fun lastResult_nullBeforeFirstFlush() {
        assertNull(flusher().lastResult)
    }

    @Test
    fun priorityZero_sentFirst() {
        seed(3)
        seed(1, priority = 0)
        flusher().flushOnce()
        assertEquals("id-0-0", requests.single().first())
    }

    @Test
    fun flushAll_drainsQueueAndReturnsEmpty() = runTest {
        seed(120)
        assertEquals(FlushOutcome.EMPTY, flusher().flushAll())
        assertEquals(0, queue.pendingCount())
        assertEquals(listOf(50, 50, 20), requests.map { it.size })
    }

    @Test
    fun flushAll_stopsOnFailure() = runTest {
        seed(120)
        results += SendResult.Accepted
        results += SendResult.NetworkError(IOException("x"))
        assertEquals(FlushOutcome.FAILED, flusher().flushAll())
        assertEquals(2, requests.size)
        assertEquals(70, queue.pendingCount())
    }

    @Test
    fun flushAll_continuesAfterDrop() = runTest {
        seed(120)
        results += SendResult.Drop(400)
        assertEquals(FlushOutcome.EMPTY, flusher().flushAll())
        assertEquals(3, requests.size)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun flushAll_respectsRateLimit() = runTest {
        seed(12 * 50)
        assertEquals(FlushOutcome.EMPTY, flusher().flushAll(maxBatchesPerSecond = 5))
        assertEquals(12, requests.size)
        assertTrue("virtual time ${currentTime}ms", currentTime >= 2_000)
    }

    @Test
    fun flushAll_noDelayWhenQueueEmpty() = runTest {
        assertEquals(FlushOutcome.EMPTY, flusher().flushAll())
        assertEquals(0L, currentTime)
    }

    @Test
    fun tooLarge_claimSizeResetsOnceQueueDrains() {
        seed(8)
        results += SendResult.TooLarge
        val f = flusher()
        f.flushOnce() // 8 -> too large, cap 4
        while (queue.pendingCount() > 0) f.flushOnce()
        assertEquals(FlushOutcome.EMPTY, f.flushOnce())
        seed(30)
        f.flushOnce()
        assertEquals(30, requests.last().size)
    }

    @Test
    fun concurrentCalls_neverOverlapAndNeverDuplicate() {
        seed(400)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val concurrentSender = Sender { body ->
            val n = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            seen += idsOf(body)
            Thread.sleep(5)
            inFlight.decrementAndGet()
            SendResult.Accepted
        }
        val f = Flusher(queue, encoder, concurrentSender, killSwitch, clock, Backoff(random = maxJitter), scheduler)
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        repeat(4) {
            pool.execute {
                try {
                    while (f.flushOnce() != FlushOutcome.EMPTY) Unit
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, maxInFlight.get())
        assertEquals(400, seen.size)
        assertEquals(400, seen.toSet().size)
        assertFalse(queue.pendingCount() > 0)
    }

    @Test
    fun senderThrowsError_batchReleased_errorPropagates() {
        seed(3)
        val f = Flusher(
            queue, encoder, Sender { throw OutOfMemoryError("oom") }, killSwitch, clock,
            Backoff(random = maxJitter), scheduler,
        )
        try {
            f.flushOnce()
            fail("expected OutOfMemoryError")
        } catch (e: OutOfMemoryError) {
            // propagated by design, after the batch was released
        }
        assertEquals(0, queue.inFlightCount())
        assertEquals(3, queue.pendingCount())
        // The lock was released by the unwinding: the batch can be claimed and sent again.
        assertEquals(FlushOutcome.SENT, flusher().flushOnce())
    }

    @Test
    fun encoderThrowsError_batchReleased_errorPropagates() {
        seed(3)
        encoderContext = { throw StackOverflowError() }
        val f = flusher()
        try {
            f.flushOnce()
            fail("expected StackOverflowError")
        } catch (e: StackOverflowError) {
            // propagated
        }
        assertEquals(0, queue.inFlightCount())
        assertEquals(3, queue.pendingCount())
    }

    @Test
    fun completeThrowsAfterAccepted_batchNotLeftInFlight() {
        seed(3)
        val faulty = FaultyQueue(queue, failComplete = IllegalStateException("sqlite"))
        val f = flusher(faulty)
        // Server accepted it, so releasing may cause a duplicate delivery; acceptable since ids are stable.
        assertEquals(FlushOutcome.FAILED, f.flushOnce())
        assertEquals(0, queue.inFlightCount())
        assertEquals(3, queue.pendingCount())
        assertEquals("queue_error", f.lastResult)
        faulty.failComplete = null
        assertEquals(FlushOutcome.SENT, f.flushOnce())
        assertEquals(requests[0], requests[1])
    }

    @Test
    fun unauthorizedWithThrowingRelease_stillTripsKillSwitch() {
        seed(2)
        results += SendResult.Unauthorized
        val faulty = FaultyQueue(queue, failRelease = IllegalStateException("sqlite"))
        val f = flusher(faulty)
        assertEquals(FlushOutcome.FAILED, f.flushOnce())
        assertTrue(killSwitch.isActive())
        assertEquals("queue_error", f.lastResult)
    }

    @Test
    fun releaseThrowingTwice_doesNotMaskOrCrash() {
        seed(2)
        results += SendResult.NetworkError(IOException("x"))
        val faulty = FaultyQueue(queue, failRelease = IllegalStateException("sqlite"))
        assertEquals(FlushOutcome.FAILED, flusher(faulty).flushOnce())
        assertEquals(2, faulty.releaseCalls) // original attempt + finally retry
    }

    @Test
    fun pausedUntil_usesClockAfterSendReturns() {
        seed(2)
        val slowSender = Sender {
            clock.advance(7_000)
            SendResult.NetworkError(IOException("slow"))
        }
        val f = Flusher(queue, encoder, slowSender, killSwitch, clock, Backoff(random = maxJitter), scheduler)
        val start = clock.now
        f.flushOnce()
        assertEquals(start + 7_000 + 2_000, f.pausedUntilMs)
    }
}
