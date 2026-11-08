package id.art.tracker.replay.rrweb

import id.art.tracker.core.Json
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the full-snapshot shape. Not proof of rrweb compatibility: that is validated by
 * playing real output in rrweb-player (Task 25). Update the golden file only after reviewing the diff by hand.
 */
class RrwebGoldenTest {
    private fun layout(): Wireframe = Wireframe(
        id = 100, x = 0, y = 0, width = 360, height = 640, type = WireframeType.CONTAINER,
        background = 0xFFFFFFFF.toInt(),
        children = listOf(
            Wireframe(
                id = 101, x = 16, y = 40, width = 328, height = 48, type = WireframeType.TEXT,
                text = "Welcome back", textColor = 0xFF212121.toInt(), textSizeSp = 20f,
            ),
            Wireframe(
                id = 102, x = 16, y = 104, width = 328, height = 56, type = WireframeType.INPUT,
                text = "********", background = 0xFFF5F5F5.toInt(), masked = true,
                textColor = 0xFF424242.toInt(), textSizeSp = 16f,
            ),
            Wireframe(
                id = 103, x = 16, y = 176, width = 328, height = 160, type = WireframeType.CONTAINER,
                alpha = 0.5f, background = 0x80000000.toInt(),
                children = listOf(
                    Wireframe(id = 104, x = 24, y = 184, width = 64, height = 64, type = WireframeType.IMAGE),
                    Wireframe(
                        id = 105, x = 96, y = 184, width = 200, height = 32, type = WireframeType.BUTTON,
                        text = "Sign in", background = 0xFF1976D2.toInt(), textColor = 0xFFFFFFFF.toInt(), textSizeSp = 14f,
                    ),
                ),
            ),
            Wireframe(id = 106, x = 0, y = 352, width = 360, height = 200, type = WireframeType.WEB, masked = true),
            Wireframe(id = 107, x = 0, y = 560, width = 360, height = 80, type = WireframeType.CONTAINER, summarized = true),
        ),
    )

    @Test
    fun golden_fullSnapshotMatchesPinnedJson() {
        val actual = Json.encode(RrwebEncoder().fullSnapshot(layout(), 1_700_000_000_000L))
        val stream = javaClass.classLoader!!.getResourceAsStream("rrweb/golden_full_snapshot.json")
        assertTrue("missing rrweb/golden_full_snapshot.json; actual=$actual", stream != null)
        val expected = stream!!.bufferedReader().use { it.readText() }
        assertEquals("golden mismatch (key order included)", compact(expected), actual)
        JSONObject(actual) // also parses
    }

    /** Strips insignificant whitespace (outside string literals) so the golden file can be pretty-printed. */
    private fun compact(json: String): String {
        val out = StringBuilder()
        var inString = false
        var escaped = false
        for (c in json) {
            when {
                inString -> {
                    out.append(c)
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                }
                c == '"' -> { inString = true; out.append(c) }
                !c.isWhitespace() -> out.append(c)
            }
        }
        return out.toString()
    }
}
