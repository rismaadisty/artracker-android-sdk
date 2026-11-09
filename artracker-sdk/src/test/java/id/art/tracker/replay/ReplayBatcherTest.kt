package id.art.tracker.replay

import id.art.tracker.testutil.FakeClock
import id.art.tracker.transport.NewReplayBatch
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Random
import java.util.zip.GZIPInputStream

class ReplayBatcherTest {
    private val clock = FakeClock(100_000L)
    private val batches = mutableListOf<NewReplayBatch>()
    private var userId: String? = "user-1"

    private fun batcher(maxBytes: Int = 256 * 1024, windowMs: Long = 10_000) =
        ReplayBatcher(ReplaySink { batches += it }, clock, "staging", { userId }, maxBytes, windowMs)

    private fun event(type: Int, ts: Long?, data: Any? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to type).also {
            if (ts != null) it["timestamp"] = ts
            if (data != null) it["data"] = data
        }

    private fun chunk(vararg events: Map<String, Any?>, start: Long? = null, end: Long? = null): Map<String, Any?> =
        linkedMapOf("seq" to 0, "start" to start, "end" to end, "full_snapshot" to false, "events" to events.toList())

    private fun gunzip(body: ByteArray): JSONArray =
        JSONArray(GZIPInputStream(ByteArrayInputStream(body)).readBytes().toString(Charsets.UTF_8))

    @Test
    fun body_isGzipJsonArrayOfEventsInOrder() {
        val b = batcher()
        b.add("session-A", chunk(event(4, 10), event(2, 11)))
        b.add("session-A", chunk(event(3, 20)))
        b.close()

        assertEquals(1, batches.size)
        val array = gunzip(batches[0].gzipBody)
        assertEquals(listOf(4, 2, 3), (0 until array.length()).map { array.getJSONObject(it).getInt("type") })
        assertEquals(3, batches[0].eventCount)
        assertEquals("session-A", batches[0].sessionId)
    }

    @Test
    fun timestamps_areMinAndMaxOfEvents() {
        val b = batcher()
        b.add("session-A", chunk(event(4, 10), event(2, 11)))
        b.add("session-A", chunk(event(3, 20)))
        b.close()

        assertEquals(10L, batches[0].firstTsMs)
        assertEquals(20L, batches[0].lastTsMs)
    }

    @Test
    fun timestamps_fallBackToChunkRangeThenClock() {
        val b = batcher()
        b.add("S1-session", chunk(event(3, null), start = 40, end = 90))
        b.close()
        b.add("S2-session", chunk(event(3, null)))
        b.close()

        assertEquals(40L, batches[0].firstTsMs)
        assertEquals(90L, batches[0].lastTsMs)
        assertEquals(100_000L, batches[1].firstTsMs)
        assertEquals(100_000L, batches[1].lastTsMs)
    }

    @Test
    fun closesAtMaxBytes() {
        // Each chunk is ~60 bytes of JSON: one fits in 100, two do not.
        val b = batcher(maxBytes = 100)
        val data = "x".repeat(40)
        b.add("session-A", chunk(event(3, 1, data)))
        assertEquals(0, batches.size)
        b.add("session-A", chunk(event(3, 2, data)))
        // The second chunk would pass the limit, so it started a new batch and the first one was closed.
        assertEquals(1, batches.size)
        assertEquals(1, batches[0].eventCount)
        b.close()
        assertEquals(2, batches.size)
        assertEquals(1, batches[1].eventCount)
    }

    @Test
    fun closeIfDue_closesOnlyAfterWindow() {
        val b = batcher(windowMs = 10_000)
        b.add("session-A", chunk(event(3, 1)))

        clock.advance(9_999)
        b.closeIfDue()
        assertEquals(0, batches.size)

        clock.advance(1)
        b.closeIfDue()
        assertEquals(1, batches.size)

        // Nothing open any more: a second call is a no-op.
        clock.advance(60_000)
        b.closeIfDue()
        assertEquals(1, batches.size)
    }

    @Test
    fun sessionChange_closesPreviousBatchFirst() {
        val b = batcher()
        b.add("session-A", chunk(event(3, 1)))
        b.add("session-B", chunk(event(3, 2)))

        assertEquals(listOf("session-A"), batches.map { it.sessionId })
        b.close()
        assertEquals(listOf("session-A", "session-B"), batches.map { it.sessionId })
    }

    @Test
    fun markError_flagsOpenBatch_andResetsAfterClose() {
        val b = batcher()
        b.add("session-A", chunk(event(3, 1)))
        b.markError()
        b.close()
        b.add("session-A", chunk(event(3, 2)))
        b.close()

        assertTrue(batches[0].hasError)
        assertFalse(batches[1].hasError)
    }

    @Test
    fun markError_beforeAnyEvents_flagsNextBatch() {
        val b = batcher()
        b.markError()
        b.add("session-A", chunk(event(3, 1)))
        b.close()
        b.add("session-A", chunk(event(3, 2)))
        b.close()

        assertTrue(batches[0].hasError)
        assertFalse(batches[1].hasError)
    }

    @Test
    fun discard_dropsOpenBatch_sinkNeverCalled() {
        val b = batcher()
        b.add("session-A", chunk(event(3, 1)))
        b.discard()
        b.close()
        clock.advance(60_000)
        b.closeIfDue()

        assertEquals(0, batches.size)
    }

    @Test
    fun emptyEvents_chunkIgnored() {
        val b = batcher()
        b.add("session-A", chunk())
        b.add("session-A", mapOf("seq" to 0))
        b.close()

        assertEquals(0, batches.size)
    }

    @Test
    fun batchOverFiveMiBGzip_isDropped() {
        val b = batcher(maxBytes = 64 * 1024 * 1024)
        // Printable ASCII without '"' and '\\': ~6.5 bits of entropy per char, so 8 MiB gzips to ~6.5 MiB.
        val alphabet = (0x23..0x5B).map { it.toChar() } + (0x5D..0x7E).map { it.toChar() }
        val random = Random(42)
        val noise = String(CharArray(8 * 1024 * 1024) { alphabet[random.nextInt(alphabet.size)] })
        b.add("session-A", chunk(event(3, 1, noise)))
        b.close()

        assertEquals(0, batches.size)
        // The dropped batch does not poison the next one.
        b.add("session-A", chunk(event(3, 2)))
        b.close()
        assertEquals(1, batches.size)
    }

    @Test
    fun batch_carriesEnvironmentAndCurrentUserId() {
        val b = batcher()
        b.add("session-A", chunk(event(3, 1)))
        userId = "user-2"
        b.close()
        userId = null
        b.add("session-A", chunk(event(3, 2)))
        b.close()

        assertEquals("staging", batches[0].environment)
        // The user id is read when the batch closes, so it reflects identify() calls made during the batch.
        assertEquals("user-2", batches[0].userId)
        assertNull(batches[1].userId)
    }
}
