package id.art.tracker

import id.art.tracker.core.ItemFactory
import id.art.tracker.privacy.AttributeRedactor
import id.art.tracker.telemetry.Breadcrumbs
import id.art.tracker.telemetry.CrashItemBuilder
import id.art.tracker.testutil.FakeClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelemetryItemJsonTest {
    private val items = ItemFactory(FakeClock(1_000), { "s1" }, { null })

    private fun nested(levels: Int): Map<String, Any?> {
        var m: Map<String, Any?> = mapOf("leaf" to "deepest-text")
        repeat(levels - 1) { m = mapOf("childNodes" to listOf(m)) }
        return m
    }

    @Test
    fun itemNamedReplayChunk_isCutAtTheNormalDepth() {
        val json = items.session("replay_chunk", mapOf("events" to listOf(nested(100)))).toJson()
        assertTrue(json.contains("\"[max_depth]\""))
        assertFalse(json.contains("deepest-text"))
        JSONObject(json) // still valid JSON
    }

    @Test
    fun nonReplayItem_withDeepProps_isStillCutAt32() {
        val json = items.event("checkout", mapOf("p" to nested(40))).toJson()
        assertTrue(json.contains("\"[max_depth]\""))
        assertFalse(json.contains("deepest-text"))
    }

    @Test
    fun eventNamedReplayChunk_doesNotGetReplayDepth() {
        // Nothing gets the larger replay limit through toJson any more (replay has its own upload path).
        val json = items.event("replay_chunk", mapOf("p" to nested(40))).toJson()
        assertTrue(json.contains("\"[max_depth]\""))
    }

    @Test
    fun parsesACrashItemBuiltByCrashItemBuilder() {
        // Production warms the redactor's regexes at startup; cold, they eat the builder's 40 ms breadcrumb budget.
        AttributeRedactor.redactString("warm-up 1 a@b.co")
        val crumbs = Breadcrumbs(clock = FakeClock(1_000))
        crumbs.add("screen", "Checkout", mapOf("previous" to "Cart"))
        val (id, json) = CrashItemBuilder.build(
            thread = Thread.currentThread(),
            t = IllegalStateException("bad state"),
            nowMs = 1_762_331_598_050,
            sessionId = "sess-1",
            userId = "u-1",
            appVersion = "1.2.3",
            screen = "Checkout",
            breadcrumbs = crumbs,
        )

        val item = telemetryItemFromJson(json)!!

        assertEquals(id, item.id)
        assertEquals(ItemType.LOG, item.type)
        assertEquals(LogLevel.FATAL, item.level)
        assertEquals("crash", item.name)
        assertEquals("java.lang.IllegalStateException: bad state", item.message)
        assertEquals(1_762_331_598_050, item.timestampMs)
        assertEquals("sess-1", item.sessionId)
        assertEquals("u-1", item.userId)
        assertEquals(false, item.attributes["handled"])
        val breadcrumbs = item.attributes["breadcrumbs"] as List<*>
        val first = breadcrumbs.single() as Map<*, *>
        assertEquals("Checkout", first["message"])
        assertEquals("Cart", (first["data"] as Map<*, *>)["previous"])
    }

    @Test
    fun longAttributesSurviveTheRoundTrip() {
        val json = items.event("x", mapOf("big" to 5_000_000_000L, "small" to 7)).toJson()

        val attrs = telemetryItemFromJson(json)!!.attributes

        assertEquals(5_000_000_000L, attrs["big"])
        assertEquals(7, attrs["small"])
    }

    @Test
    fun jsonNullBecomesKotlinNull() {
        val attrs = telemetryItemFromJson(items.event("x", mapOf("k" to null)).toJson())!!.attributes

        assertTrue(attrs.containsKey("k"))
        assertNull(attrs["k"])
    }

    @Test
    fun anEventRoundTrips() {
        val original = items.event("checkout", mapOf("total" to 12.5, "tags" to listOf("a", "b")))

        assertEquals(original, telemetryItemFromJson(original.toJson()))
    }

    @Test
    fun missingSessionIdReturnsNull() {
        val json = items.event("x").toJson().replace("\"session_id\":\"s1\",", "")
        assertFalse(json.contains("session_id"))
        assertNull(telemetryItemFromJson(json))
    }

    @Test
    fun anUnknownTypeOrBrokenJsonReturnsNull() {
        assertNull(telemetryItemFromJson(items.event("x").toJson().replace("\"type\":\"event\"", "\"type\":\"bogus\"")))
        assertNull(telemetryItemFromJson("{not json"))
        assertNotNull(telemetryItemFromJson(items.event("x").toJson()))
    }
}
