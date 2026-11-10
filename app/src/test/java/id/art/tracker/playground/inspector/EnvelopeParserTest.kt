package id.art.tracker.playground.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeParserTest {
    @Test
    fun parsesTwoItemsWithTheirFields() {
        val json = envelope(
            itemJson("checkout_clicked", attributes = "{\"total\":120000,\"plan\":\"pro\"}", userId = "u-1"),
            itemJson("http_request", type = "log", level = "warn", message = "GET 404"),
        )
        val items = EnvelopeParser.parse(json)
        assertEquals(2, items.size)
        val a = items[0]
        assertEquals("event", a.type)
        assertEquals("checkout_clicked", a.name)
        assertEquals("u-1", a.userId)
        assertEquals("session-1", a.sessionId)
        assertEquals("120000", a.attributes["total"])
        assertEquals("pro", a.attributes["plan"])
        assertEquals(ItemSource.SENT_PAYLOAD, a.source)
        assertEquals(1_794_045_600_000L, a.timestampMs)
        val b = items[1]
        assertEquals("log", b.type)
        assertEquals("warn", b.level)
        assertEquals("GET 404", b.message)
        assertNull(b.userId)
    }

    @Test
    fun longAttributeValuesAreClippedTo200Chars() {
        val items = EnvelopeParser.parse(envelope(itemJson("e", attributes = "{\"k\":\"" + "v".repeat(5_000) + "\"}")))
        assertEquals(200, items.single().attributes.getValue("k").length)
        assertTrue(items.single().truncated)
    }

    @Test
    fun invalidInputNeverThrowsAndYieldsNothing() {
        assertTrue(EnvelopeParser.parse("").isEmpty())
        assertTrue(EnvelopeParser.parse("not json").isEmpty())
        assertTrue(EnvelopeParser.parse("{\"items\":[{\"id\":").isEmpty())
        assertTrue(EnvelopeParser.parse("{\"items\":\"nope\"}").isEmpty())
        assertTrue(EnvelopeParser.parse("[1,2,3]").isEmpty())
    }

    @Test
    fun resultIsCappedAt500Items() {
        val one = itemJson("e")
        val json = "{\"items\":[" + List(50_000) { one }.joinToString(",") + "]}"
        assertEquals(500, EnvelopeParser.parse(json).size)
    }
}
