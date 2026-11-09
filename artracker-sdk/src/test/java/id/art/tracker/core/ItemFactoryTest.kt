package id.art.tracker.core

import id.art.tracker.LogLevel
import id.art.tracker.priority
import id.art.tracker.testutil.FakeClock
import id.art.tracker.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemFactoryTest {
    private fun factory(
        clock: FakeClock = FakeClock(1000),
        userId: String? = "u1",
    ) = ItemFactory(clock, { "s1" }, { userId })

    @Test
    fun event_hasUuidV7AndTimestampFromClock() {
        val item = factory().event("tap")
        assertEquals(1000L, item.timestampMs)
        assertEquals('7', item.id[14])
        assertEquals("s1", item.sessionId)
        assertEquals("u1", item.userId)
    }

    @Test
    fun longName_truncatedTo255() {
        assertEquals(255, factory().event("a".repeat(300)).name.length)
    }

    @Test
    fun longMessage_truncatedTo4096() {
        val item = factory().log(LogLevel.INFO, "n", "m".repeat(5000))
        assertEquals(4096, item.message!!.length)
    }

    @Test
    fun longUserId_truncatedTo128() {
        assertEquals(128, factory(userId = "x".repeat(200)).event("e").userId!!.length)
    }

    @Test
    fun longSessionId_truncatedTo128() {
        val item = ItemFactory(FakeClock(), { "s".repeat(200) }, { null }).event("e")
        assertEquals(128, item.sessionId.length)
    }

    @Test
    fun toJson_eventOmitsLevel() {
        val json = JSONObject(factory().event("tap", mapOf("k" to 1)).toJson())
        assertFalse(json.has("level"))
        assertEquals("event", json.getString("type"))
        assertEquals("1970-01-01T00:00:01.000Z", json.getString("timestamp"))
        assertEquals("tap", json.getString("name"))
        assertEquals("s1", json.getString("session_id"))
        assertEquals("u1", json.getString("user_id"))
        assertEquals(1, json.getJSONObject("attributes").getInt("k"))
    }

    @Test
    fun toJson_logIncludesLevel() {
        val json = JSONObject(factory().log(LogLevel.ERROR, "boom", "msg").toJson())
        assertEquals("error", json.getString("level"))
        assertEquals("log", json.getString("type"))
        assertEquals("msg", json.getString("message"))
    }

    @Test
    fun toJson_omitsNullUserIdAndMessage() {
        val json = JSONObject(factory(userId = null).event("e").toJson())
        assertFalse(json.has("user_id"))
        assertFalse(json.has("message"))
        assertTrue(json.has("attributes"))
    }

    @Test
    fun priority_ordering() {
        val f = factory()
        assertEquals(0, f.log(LogLevel.FATAL, "crash", null).priority())
        assertEquals(1, f.log(LogLevel.ERROR, "err", null).priority())
        assertEquals(2, f.event("tap").priority())
        assertEquals(2, f.session("replay_chunk").priority())
        assertEquals(2, f.log(LogLevel.INFO, "info", null).priority())
    }

    @Test
    fun priority_nameReplayChunkGetsNoSpecialTreatment() {
        val f = factory()
        // Replay no longer travels through the ingest queue: an item with that name is like any other.
        assertEquals(2, f.session("replay_chunk").priority())
        assertEquals(2, f.event("replay_chunk").priority())
    }
}
