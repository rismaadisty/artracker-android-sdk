package id.art.tracker.transport

import id.art.tracker.BeforeSend
import id.art.tracker.LogLevel
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ItemFactory
import id.art.tracker.core.KillSwitch
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.FakeQueue
import id.art.tracker.testutil.InMemoryStore
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every insert call (group) and delegates to a [FakeQueue]. */
private class RecordingQueue(val delegate: FakeQueue = FakeQueue()) : ItemQueue by delegate {
    val groups = ArrayList<List<StoredItem>>()
    val stored: List<StoredItem> get() = groups.flatten()

    override fun insert(items: List<StoredItem>) {
        groups += items
        delegate.insert(items)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EventPipelineTest {
    private val clock = FakeClock(1_700_000_000_000L)
    private val queue = RecordingQueue()
    private val killSwitch = KillSwitch(InMemoryStore(), clock)
    private val factory = ItemFactory(clock, { "session-1" }, { null })

    private val requests = ArrayList<List<String>>()
    private val sender = Sender { body ->
        val json = GZIPInputStream(ByteArrayInputStream(body)).use { String(it.readBytes(), Charsets.UTF_8) }
        val items = JSONObject(json).getJSONArray("items")
        requests += (0 until items.length()).map { items.getJSONObject(it).getString("id") }
        SendResult.Accepted
    }
    private val flusher = Flusher(
        queue,
        PayloadEncoder { mapOf("environment" to "test") },
        sender,
        killSwitch,
        clock,
        Backoff(),
        BackgroundScheduler { },
    )

    private fun TestScope.pipeline(
        beforeSend: BeforeSend? = null,
        flushIntervalMs: Long = 10_000L,
        scope: CoroutineScope = backgroundScope,
    ) = EventPipeline(scope, queue, flusher, clock, beforeSend, flushIntervalMs)

    private fun payloadOf(id: String): String = queue.stored.first { it.id == id }.payloadJson

    @Test
    fun redactionApplied() = runTest {
        val p = pipeline()
        val item = factory.event("login", mapOf("password" to "x", "screen" to "home"))
        assertTrue(p.enqueue(item))
        p.awaitIdle()

        val payload = payloadOf(item.id)
        assertTrue(payload, payload.contains("[redacted]"))
        assertFalse(payload, payload.contains("\"x\""))
        assertTrue(payload, payload.contains("home"))
    }

    @Test
    fun beforeSendNull_drops() = runTest {
        val p = pipeline(beforeSend = BeforeSend { null })
        assertTrue(p.enqueue(factory.event("a")))
        p.awaitIdle()

        assertTrue(queue.stored.isEmpty())
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun beforeSendThrows_keepsOriginal() = runTest {
        val p = pipeline(beforeSend = BeforeSend { error("boom") })
        val item = factory.event("original", mapOf("k" to "v"))
        p.enqueue(item)
        p.awaitIdle()

        assertEquals(1, queue.stored.size)
        val payload = JSONObject(payloadOf(item.id))
        assertEquals("original", payload.getString("name"))
        assertEquals("v", payload.getJSONObject("attributes").getString("k"))
    }

    @Test
    fun beforeSendModifies_modifiedItemIsStored() = runTest {
        val p = pipeline(beforeSend = BeforeSend { it.copy(name = "renamed") })
        val item = factory.event("original")
        p.enqueue(item)
        p.awaitIdle()

        assertEquals("renamed", JSONObject(payloadOf(item.id)).getString("name"))
    }

    @Test
    fun replayChunk_skipsRedactor() = runTest {
        val p = pipeline()
        val chunk = factory.event("replay_chunk", mapOf("data" to "a@b.com"))
        val other = factory.event("other", mapOf("data" to "a@b.com"))
        p.enqueue(chunk)
        p.enqueue(other)
        p.awaitIdle()

        assertTrue(payloadOf(chunk.id).contains("a@b.com"))
        assertEquals(3, queue.stored.first { it.id == chunk.id }.priority)
        assertFalse(payloadOf(other.id).contains("a@b.com"))
    }

    @Test
    fun faultyItem_isSkipped_andPipelineKeepsRunning() = runTest {
        val p = pipeline()
        val poison = object {
            override fun toString(): String = throw IllegalStateException("cannot stringify")
        }
        val bad = factory.event("bad", mapOf("k" to poison))
        val good = factory.event("good")
        p.enqueue(bad)
        p.enqueue(good)
        p.awaitIdle()

        assertEquals(listOf(good.id), queue.stored.map { it.id })
        val later = factory.event("later")
        p.enqueue(later)
        p.awaitIdle()
        assertEquals(listOf(good.id, later.id), queue.stored.map { it.id })
    }

    @Test
    fun fatal_triggersImmediateFlush() = runTest {
        val p = pipeline()
        val fatal = factory.log(LogLevel.FATAL, "crash", "boom")
        p.enqueue(fatal)
        runCurrent()

        assertEquals(listOf(listOf(fatal.id)), requests)
        assertTrue("flushed at virtual ${currentTime}ms", currentTime < 1_000)
        p.awaitIdle()
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun normalItem_doesNotTriggerFlush() = runTest {
        val p = pipeline()
        p.enqueue(factory.event("a"))
        p.awaitIdle()

        assertEquals(1, queue.pendingCount())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun pendingAtLeast50_triggersFlush() = runTest {
        val p = pipeline()
        val items = (0 until 50).map { factory.event("e$it") }
        items.forEach { p.enqueue(it) }
        p.awaitIdle()

        assertEquals(1, requests.size)
        assertEquals(50, requests.single().size)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun persistsInGroupsOf20() = runTest {
        val p = pipeline()
        repeat(45) { p.enqueue(factory.event("e$it")) }
        p.awaitIdle()

        assertEquals(listOf(20, 20, 5), queue.groups.map { it.size })
    }

    @Test
    fun partialGroup_persistedAfter1000ms() = runTest {
        val p = pipeline()
        repeat(3) { p.enqueue(factory.event("e$it")) }
        runCurrent()
        assertTrue(queue.groups.isEmpty())

        advanceTimeBy(999)
        runCurrent()
        assertTrue(queue.groups.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(3), queue.groups.map { it.size })
    }

    @Test
    fun timer_flushesOnlyWhenPending() = runTest {
        val p = pipeline(flushIntervalMs = 10_000)
        p.startTimer()

        advanceTimeBy(10_001)
        runCurrent()
        p.awaitIdle()
        assertTrue("empty queue must not flush", requests.isEmpty())

        p.enqueue(factory.event("a"))
        p.awaitIdle()
        assertTrue(requests.isEmpty())

        advanceTimeBy(10_000)
        runCurrent()
        p.awaitIdle()
        assertEquals(1, requests.size)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun stopTimer_stopsFlushing() = runTest {
        val p = pipeline(flushIntervalMs = 10_000)
        p.startTimer()
        p.stopTimer()
        p.enqueue(factory.event("a"))
        p.awaitIdle()

        advanceTimeBy(60_000)
        runCurrent()
        p.awaitIdle()
        assertTrue(requests.isEmpty())
        assertEquals(1, queue.pendingCount())
    }

    @Test
    fun requestFlush_flushesPending() = runTest {
        val p = pipeline()
        p.enqueue(factory.event("a"))
        p.awaitIdle()
        p.requestFlush()
        p.awaitIdle()

        assertEquals(1, requests.size)
    }

    @Test
    fun flushNow_includesUnpersistedPartialGroup_inOneRequest() = runTest {
        val p = pipeline()
        // Distinct timestamps: the queue orders by priority, then age, then id.
        val items = (0 until 3).map { clock.advance(1); factory.event("e$it") }
        items.forEach { p.enqueue(it) }

        p.flushNow() // before the 1000 ms group window elapses; nothing is persisted yet
        p.awaitIdle()

        assertEquals(listOf(items.map { it.id }), requests)
        assertEquals(listOf(3), queue.groups.map { it.size })
        assertEquals(0, queue.pendingCount())
        assertTrue("took ${currentTime}ms of virtual time", currentTime < 1_000)
    }

    @Test
    fun flushNow_includesAllEnqueuedItems_beyondGroupSize() = runTest {
        val p = pipeline()
        val items = (0 until 45).map { clock.advance(1); factory.event("e$it") }
        items.forEach { p.enqueue(it) }

        p.flushNow()
        p.awaitIdle()

        assertEquals(items.map { it.id }, queue.stored.map { it.id })
        assertEquals(listOf(20, 20, 5), queue.groups.map { it.size })
        // Sent in order, in batches of at most 50: here a single request with all 45.
        assertEquals(listOf(items.map { it.id }), requests)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun flushNow_doesNotBlockCaller() = runTest {
        val p = pipeline()
        p.enqueue(factory.event("a"))
        p.flushNow()
        // The consumer has not run yet (StandardTestDispatcher): the call returned without doing any work.
        assertTrue(queue.groups.isEmpty())
        assertTrue(requests.isEmpty())
        p.awaitIdle()
        assertEquals(1, requests.size)
    }

    @Test
    fun requestFlush_isOrderedAfterEnqueuedItems() = runTest {
        val p = pipeline()
        val a = factory.event("a")
        p.enqueue(a)
        p.requestFlush()
        p.awaitIdle()

        assertEquals(listOf(listOf(a.id)), requests)
    }

    @Test
    fun enqueue_neverBlocks() = runTest {
        // The consumer cannot run until this test body suspends (StandardTestDispatcher), i.e. it is held.
        val p = pipeline()
        val accepted = (0 until 5_000).count { p.enqueue(factory.event("e$it")) }
        assertEquals(5_000, accepted)

        p.awaitIdle()
        assertNotNull(queue.stored)
        assertEquals(1_000, queue.stored.size)
        assertTrue(queue.pendingCount() <= 1_000)
        // DROP_OLDEST: the newest 1000 survive.
        assertEquals("e4999", JSONObject(queue.stored.last().payloadJson).getString("name"))
        assertEquals("e4000", JSONObject(queue.stored.first().payloadJson).getString("name"))
    }

    @Test
    fun awaitIdle_returnsImmediatelyWhenNothingEnqueued() = runTest {
        pipeline().awaitIdle()
        assertEquals(0L, currentTime)
    }

    @Test
    fun enqueueAfterScopeCancelled_doesNotThrowOrHang() = runTest {
        val job = Job()
        val p = pipeline(scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job))
        runCurrent()
        job.cancel()
        runCurrent()

        assertTrue(p.enqueue(factory.event("late")))
        p.awaitIdle()
        assertTrue(queue.stored.isEmpty())
    }
}
