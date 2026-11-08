package id.art.tracker

import id.art.tracker.core.ItemFactory
import id.art.tracker.testutil.FakeClock
import org.json.JSONObject
import org.junit.Assert.assertFalse
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
    fun replayChunk_isEncodedWithReplayDepth() {
        val json = items.session("replay_chunk", mapOf("events" to listOf(nested(100)))).toJson()
        assertFalse(json.contains("[max_depth]"))
        assertTrue(json.contains("deepest-text"))
        JSONObject(json) // valid JSON
    }

    @Test
    fun nonReplayItem_withDeepProps_isStillCutAt32() {
        val json = items.event("checkout", mapOf("p" to nested(40))).toJson()
        assertTrue(json.contains("\"[max_depth]\""))
        assertFalse(json.contains("deepest-text"))
    }

    @Test
    fun eventNamedReplayChunk_doesNotGetReplayDepth() {
        // Only the SDK's own session item gets the larger limit; a user event that borrows the name does not.
        val json = items.event("replay_chunk", mapOf("p" to nested(40))).toJson()
        assertTrue(json.contains("\"[max_depth]\""))
    }
}
