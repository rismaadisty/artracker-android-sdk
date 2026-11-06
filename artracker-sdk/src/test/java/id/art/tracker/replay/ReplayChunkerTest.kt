package id.art.tracker.replay

import id.art.tracker.core.Json
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import id.art.tracker.replay.rrweb.RrwebEncoder
import java.util.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class ReplayChunkerTest {
    private val limit = 24 * 1024

    private fun bytes(chunk: Map<String, Any?>): Int =
        Json.encode(chunk, Json.REPLAY_MAX_DEPTH).toByteArray(Charsets.UTF_8).size

    private fun events(chunk: Map<String, Any?>) = chunk["events"] as List<Map<String, Any?>>

    /** An incremental mutation event carrying [textBytes] of text. */
    private fun mutation(ts: Long, textBytes: Int, char: Char = 'x'): Map<String, Any?> = mapOf(
        "type" to 3,
        "data" to mapOf(
            "source" to 0,
            "texts" to listOf(mapOf("id" to 11, "value" to char.toString().repeat(textBytes))),
            "attributes" to emptyList<Any?>(), "removes" to emptyList<Any?>(), "adds" to emptyList<Any?>(),
        ),
        "timestamp" to ts,
    )

    private fun meta(ts: Long) = mapOf("type" to 4, "data" to mapOf("href" to "android://A", "width" to 360, "height" to 640), "timestamp" to ts)

    /** A real rrweb full snapshot of [groups] x [perGroup] text nodes. */
    private fun bigSnapshot(groups: Int, perGroup: Int, ts: Long = 1_000): Map<String, Any?> {
        var id = 100
        val kids = (0 until groups).map { g ->
            Wireframe(id++, 0, g * 10, 360, 10, WireframeType.CONTAINER, children = (0 until perGroup).map {
                Wireframe(id++, 0, g * 10, 360, 10, WireframeType.TEXT, text = "label-$g-$it-" + "t".repeat(40))
            })
        }
        return RrwebEncoder().fullSnapshot(Wireframe(1, 0, 0, 360, 640, WireframeType.CONTAINER, children = kids), ts)
    }

    @Test
    fun chunksUnder24KB() {
        val chunker = ReplayChunker()
        val random = Random(1)
        val out = ArrayList<Map<String, Any?>>()
        var expectedEvents = 0
        for (i in 0 until 500) {
            val full = i % 97 == 0
            if (full) {
                out += chunker.add(meta(i.toLong()), false)
                out += chunker.add(bigSnapshot(3 + random.nextInt(5), 5 + random.nextInt(10), i.toLong()), true)
                expectedEvents += 2
            } else {
                out += chunker.add(mutation(i.toLong(), 20 + random.nextInt(6_000), ('a' + random.nextInt(26))), false)
                expectedEvents++
            }
        }
        out += chunker.drain()
        assertTrue(out.size > 10)
        for (chunk in out) {
            assertTrue("chunk of ${bytes(chunk)} bytes", bytes(chunk) <= limit)
            assertTrue(Json.encode(chunk, Json.REPLAY_MAX_DEPTH).length <= limit)
            assertEquals("android", chunk["platform"])
            assertTrue(events(chunk).isNotEmpty())
        }
        assertEquals(expectedEvents, out.sumOf { events(it).size })
        // Every event in order, none lost or duplicated.
        val ts = out.flatMap { c -> events(c).map { (it["timestamp"] as Number).toLong() } }
        assertEquals(ts.sorted(), ts)
    }

    @Test
    fun seqIncrements() {
        val chunker = ReplayChunker()
        val out = ArrayList<Map<String, Any?>>()
        repeat(40) { out += chunker.add(mutation(it.toLong(), 3_000), false) }
        out += chunker.drain()
        out += chunker.add(mutation(100, 10), false)
        out += chunker.drain()
        assertTrue(out.size >= 6)
        assertEquals((0 until out.size).toList(), out.map { (it["seq"] as Number).toInt() })
    }

    @Test
    fun startAndEnd_areFirstAndLastTimestamps() {
        val chunker = ReplayChunker()
        chunker.add(mutation(1_000, 10), false)
        chunker.add(mutation(1_500, 10), false)
        chunker.add(mutation(2_000, 10), false)
        val chunk = chunker.drain().single()
        assertEquals(1_000L, (chunk["start"] as Number).toLong())
        assertEquals(2_000L, (chunk["end"] as Number).toLong())
        assertEquals(false, chunk["full_snapshot"])
        assertNull(chunk["oversized"])
    }

    @Test
    fun flushesAt20KB() {
        val chunker = ReplayChunker()
        val emitted = ArrayList<Map<String, Any?>>()
        var added = 0
        while (emitted.isEmpty() && added < 100) {
            emitted += chunker.add(mutation(added.toLong(), 1_000), false)
            added++
        }
        val chunk = emitted.single()
        assertTrue(bytes(chunk) >= 20 * 1024)
        assertTrue(bytes(chunk) <= limit)
        assertEquals(added, events(chunk).size) // the event that crossed 20 KB is in it
        assertTrue(chunker.drain().isEmpty())
    }

    @Test
    fun drain_ofEmptyChunker_isEmpty() {
        assertTrue(ReplayChunker().drain().isEmpty())
    }

    @Test
    fun fullSnapshotStartsNewChunk() {
        val chunker = ReplayChunker()
        assertTrue(chunker.add(mutation(1, 10), false).isEmpty())
        val closed = chunker.add(meta(2), false) // Meta opens the snapshot sequence
        assertEquals(1, closed.size)
        assertEquals(false, closed[0]["full_snapshot"])
        assertEquals(listOf(3), events(closed[0]).map { it["type"] })
        assertTrue(chunker.add(bigSnapshot(1, 1, 3), true).isEmpty()) // joins the Meta, no empty chunk
        chunker.add(mutation(4, 10), false)
        val next = chunker.drain().single()
        assertEquals(true, next["full_snapshot"])
        assertEquals(listOf(4, 2, 3), events(next).map { it["type"] })
    }

    @Test
    fun fullSnapshotWithoutMeta_alsoStartsNewChunk() {
        val chunker = ReplayChunker()
        chunker.add(mutation(1, 10), false)
        val closed = chunker.add(bigSnapshot(1, 1, 3), true)
        assertEquals(1, closed.size)
        assertEquals(true, chunker.drain().single()["full_snapshot"])
    }

    @Test
    fun oversizedEvent_isTrimmedAndFlagged() {
        val snapshot = bigSnapshot(40, 20)
        assertTrue(Json.encode(snapshot, Json.REPLAY_MAX_DEPTH).length > limit)
        val chunker = ReplayChunker()
        val out = chunker.add(meta(999), false) + chunker.add(snapshot, true) + chunker.drain()
        val chunk = out.single { c -> events(c).any { it["type"] == 2 } }
        assertTrue(bytes(chunk) <= limit)
        assertEquals(true, chunk["oversized"])
        assertEquals(true, chunk["full_snapshot"])
        val event = events(chunk).single { it["type"] == 2 }
        assertEquals(true, event["oversized"])
        assertFalse(chunker.consumeDropped())

        // Valid rrweb structure retained: document > [doctype, html > [head, body > root div]].
        val document = (event["data"] as Map<String, Any?>)["node"] as Map<String, Any?>
        assertEquals(0, document["type"])
        val html = (document["childNodes"] as List<Map<String, Any?>>).single { it["tagName"] == "html" }
        val body = (html["childNodes"] as List<Map<String, Any?>>).single { it["tagName"] == "body" }
        val root = (body["childNodes"] as List<Map<String, Any?>>).single()
        assertEquals("div", root["tagName"])
        assertTrue(root.containsKey("id"))
        fun check(node: Map<String, Any?>) {
            assertTrue(node.containsKey("type") && node.containsKey("id"))
            if (node["type"] == 2) {
                assertTrue(node["childNodes"] is List<*>)
                assertTrue(node["attributes"] is Map<*, *>)
                (node["childNodes"] as List<Map<String, Any?>>).forEach(::check)
            }
        }
        check(document)
        // Parses as JSON and the shallow part of the tree (the 40 group containers) survives.
        JSONObject(Json.encode(chunk, Json.REPLAY_MAX_DEPTH))
        assertTrue((root["childNodes"] as List<*>).isNotEmpty())
    }

    @Test
    fun untrimmableEvent_isDroppedAndReported() {
        val chunker = ReplayChunker()
        chunker.add(mutation(1, 10), false)
        assertTrue(chunker.add(mutation(2, 30_000), false).isEmpty())
        assertTrue(chunker.consumeDropped())
        assertFalse(chunker.consumeDropped())
        assertEquals(1, events(chunker.drain().single()).size)
    }

    @Test
    fun measuredSize_equalsSerializedSize() {
        val chunker = ReplayChunker()
        val random = Random(3)
        var checked = 0
        for (i in 0 until 300) {
            val emitted = if (i % 50 == 0) {
                chunker.add(meta(i * 1_000_003L), false) + chunker.add(bigSnapshot(2, 3, i * 1_000_003L), true)
            } else {
                // Non-ASCII text: the measure is UTF-8 bytes, not chars.
                chunker.add(mutation(i * 1_000_003L, 10 + random.nextInt(3_000), if (i % 3 == 0) 'é' else 'q'), false)
            }
            for (chunk in emitted.takeLast(1)) {
                assertEquals(bytes(chunk), chunker.lastChunkBytes)
                checked++
            }
        }
        chunker.add(mutation(9_999_999_999L, 5, 'é'), false)
        val last = chunker.drain().single()
        assertEquals(bytes(last), chunker.lastChunkBytes)
        assertTrue(checked > 5)
    }
}
