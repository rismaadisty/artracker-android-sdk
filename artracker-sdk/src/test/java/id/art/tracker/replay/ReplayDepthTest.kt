package id.art.tracker.replay

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.PrivacyConfig
import id.art.tracker.core.ItemFactory
import id.art.tracker.core.Json
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.capture.ViewWalker
import id.art.tracker.replay.rrweb.RrwebEncoder
import id.art.tracker.testutil.FakeClock
import id.art.tracker.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ruling from the Task 22 review: deep view trees must survive the whole replay path as valid, full rrweb. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayDepthTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun fortyLevelViewTree_isFullyNestedInTheSentItem() {
        val root = FrameLayout(context)
        var parent: ViewGroup = root
        repeat(39) {
            val child = FrameLayout(context)
            parent.addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            parent = child
        }
        parent.addView(TextView(context).apply { text = "deepest-node-text" }, ViewGroup.LayoutParams(200, 50))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 1000)

        val walked = ViewWalker(MaskingPolicy(PrivacyConfig())).walk(root)
        assertNotNull(walked)
        val wireframe = walked!!
        var depth = 0
        var w = wireframe
        while (w.children.isNotEmpty()) {
            w = w.children.single()
            depth++
        }
        assertEquals(40, depth)
        assertEquals("deepest-node-text", w.text)

        val encoder = RrwebEncoder()
        val chunker = ReplayChunker()
        chunker.add(encoder.meta("android://Deep", 400, 400, 1_000), false)
        chunker.add(encoder.fullSnapshot(wireframe, 1_000), true)
        val chunk = chunker.drain().single()
        val json = ItemFactory(FakeClock(2_000), { "s1" }, { null }).session("replay_chunk", chunk).toJson()

        assertFalse(json.contains("[max_depth]"))
        assertTrue(json.contains("deepest-node-text"))
        val parsed = JSONObject(json)
        // Walk the parsed snapshot down to the text node: every level is a real object.
        var node = parsed.getJSONObject("attributes").getJSONArray("events").getJSONObject(1)
            .getJSONObject("data").getJSONObject("node")
        var found = false
        while (true) {
            val kids = node.optJSONArray("childNodes") ?: break
            if (kids.length() == 0) break
            node = kids.getJSONObject(kids.length() - 1)
            if (node.optString("textContent") == "deepest-node-text") {
                found = true
                break
            }
        }
        assertTrue(found)
        // The chunker measured exactly what is sent: the attributes as serialized inside the item.
        val attributesJson = Json.encode(chunk, Json.REPLAY_MAX_DEPTH)
        assertTrue(json.contains(",\"attributes\":$attributesJson}"))
        assertEquals(attributesJson.toByteArray(Charsets.UTF_8).size, chunker.lastChunkBytes)
    }
}
