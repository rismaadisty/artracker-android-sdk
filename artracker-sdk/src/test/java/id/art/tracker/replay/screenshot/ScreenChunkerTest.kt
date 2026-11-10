package id.art.tracker.replay.screenshot

import android.util.Base64
import id.art.tracker.replay.ReplaySink
import id.art.tracker.replay.rrweb.TouchKind
import id.art.tracker.testutil.FakeClock
import id.art.tracker.transport.NewReplayBatch
import id.art.tracker.transport.ReplayFormat
import java.io.ByteArrayInputStream
import java.util.Random
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenChunkerTest {
    private val clock = FakeClock(1_700_000_000_000L)
    private val batches = ArrayList<NewReplayBatch>()
    private var user: String? = "u-1"

    private fun chunker(maxGzipBytes: Int = 2 * 1024 * 1024, windowMs: Long = 10_000) =
        ScreenChunker(ReplaySink { batches += it }, clock, "test", { user }, maxGzipBytes, windowMs)

    private fun jpeg(size: Int, seed: Long = 1): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun frame(size: Int = 1_000, ts: Long = clock.nowMs(), screen: String = "/home", seed: Long = 1) =
        ScreenFrame(ts, screen, 360, 720, 360, 720, jpeg(size, seed), 2)

    private fun body(b: NewReplayBatch): JSONObject =
        JSONObject(GZIPInputStream(ByteArrayInputStream(b.gzipBody)).readBytes().toString(Charsets.UTF_8))

    @Test
    fun aClosedChunk_followsTheSharedContract() {
        val c = chunker()
        val f = frame(size = 500, ts = 1_700_000_000_100L)
        assertEquals(ScreenChunker.Add.ADDED, c.addFrame("s1", f))
        c.addTouch("s1", ScreenTouch(1_700_000_000_150L, 100, 200, TouchKind.DOWN))
        c.close()

        val batch = batches.single()
        assertEquals(ReplayFormat.SCREEN_V1, batch.format)
        assertEquals("s1", batch.sessionId)
        assertEquals(2, batch.eventCount) // frames + touches
        assertEquals(1_700_000_000_100L, batch.firstTsMs)
        assertEquals(1_700_000_000_150L, batch.lastTsMs)
        assertEquals("u-1", batch.userId)
        assertEquals("test", batch.environment)

        val json = body(batch)
        assertEquals("artracker-screen-v1", json.getString("format"))
        assertEquals("android", json.getString("platform"))
        assertEquals(0, json.getInt("seq"))
        val fr = json.getJSONArray("frames").getJSONObject(0)
        assertEquals(1_700_000_000_100L, fr.getLong("ts"))
        assertEquals("/home", fr.getString("screen"))
        assertEquals(360, fr.getInt("w"))
        assertEquals(720, fr.getInt("h"))
        assertEquals(360, fr.getInt("vw"))
        assertEquals(720, fr.getInt("vh"))
        assertEquals("image/jpeg", fr.getString("mime"))
        assertEquals(2, fr.getInt("masked"))
        assertArrayEquals(f.jpeg, Base64.decode(fr.getString("data"), Base64.DEFAULT))
        val t = json.getJSONArray("touches").getJSONObject(0)
        assertEquals(1_700_000_000_150L, t.getLong("ts"))
        assertEquals(100, t.getInt("x"))
        assertEquals(200, t.getInt("y"))
        assertEquals("down", t.getString("kind"))
    }

    @Test
    fun chunkSeq_countsUpWithinASession() {
        val c = chunker()
        c.addFrame("s1", frame()); c.close()
        c.addFrame("s1", frame()); c.close()
        assertEquals(listOf(0, 1), batches.map { body(it).getInt("seq") })
    }

    @Test
    fun closeIfDue_waitsForTheTenSecondWindow() {
        val c = chunker()
        c.addFrame("s1", frame())
        clock.advance(9_999)
        c.closeIfDue()
        assertTrue(batches.isEmpty())
        clock.advance(1)
        c.closeIfDue()
        assertEquals(1, batches.size)
    }

    @Test
    fun aFrameThatWouldPassTheLimit_closesTheOpenChunkFirst() {
        val c = chunker(maxGzipBytes = 6_000)
        assertEquals(ScreenChunker.Add.ADDED, c.addFrame("s1", frame(size = 2_500, seed = 1)))
        assertEquals(ScreenChunker.Add.ADDED, c.addFrame("s1", frame(size = 2_500, seed = 2)))
        assertTrue(batches.isEmpty())
        assertEquals(ScreenChunker.Add.ADDED, c.addFrame("s1", frame(size = 2_500, seed = 3)))
        assertEquals(1, batches.size)
        assertEquals(2, batches[0].eventCount)
        assertTrue(batches[0].gzipBody.size <= 6_000)
        c.close()
        assertEquals(1, batches[1].eventCount)
    }

    @Test
    fun aFrameThatCannotFitAlone_isRefused_andNothingIsAdded() {
        val c = chunker(maxGzipBytes = 6_000)
        assertEquals(ScreenChunker.Add.TOO_LARGE, c.addFrame("s1", frame(size = 20_000)))
        c.close()
        assertTrue(batches.isEmpty())
    }

    @Test
    fun aSessionChange_closesTheOpenChunk() {
        val c = chunker()
        c.addFrame("s1", frame())
        c.addFrame("s2", frame())
        assertEquals(1, batches.size)
        assertEquals("s1", batches[0].sessionId)
        c.close()
        assertEquals("s2", batches[1].sessionId)
        assertEquals(0, body(batches[1]).getInt("seq")) // seq restarts with the session
    }

    @Test
    fun aTouchWithoutAFrameOfTheSameSession_isIgnored() {
        val c = chunker()
        c.addTouch("s1", ScreenTouch(clock.nowMs(), 1, 2, TouchKind.UP))
        c.close()
        assertTrue(batches.isEmpty())
    }

    @Test
    fun markError_flagsTheOpenChunk_andDiscardDropsIt() {
        val c = chunker()
        c.addFrame("s1", frame())
        c.markError()
        c.close()
        assertTrue(batches.single().hasError)
        c.addFrame("s1", frame())
        c.discard()
        c.close()
        assertEquals(1, batches.size)
        assertFalse(batches.single().gzipBody.isEmpty())
    }
}
