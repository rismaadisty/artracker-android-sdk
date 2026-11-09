package id.art.tracker.replay

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.capture.ViewWalker
import id.art.tracker.replay.rrweb.RrwebEncoder
import id.art.tracker.testutil.FakeClock
import id.art.tracker.transport.NewReplayBatch
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.json.JSONArray
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
    fun fortyLevelViewTree_isFullyNestedInTheUploadedBatch() {
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

        val batches = ArrayList<NewReplayBatch>()
        val batcher = ReplayBatcher(ReplaySink { batches += it }, FakeClock(2_000), "test", { null })
        batcher.add("session-deep-1", chunk)
        batcher.close()
        val body = GZIPInputStream(ByteArrayInputStream(batches.single().gzipBody)).use { String(it.readBytes(), Charsets.UTF_8) }

        assertFalse(body.contains("[max_depth]"))
        assertTrue(body.contains("deepest-node-text"))
        // Walk the parsed snapshot down to the text node: every level is a real object.
        var node = JSONArray(body).getJSONObject(1).getJSONObject("data").getJSONObject("node")
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
    }
}
