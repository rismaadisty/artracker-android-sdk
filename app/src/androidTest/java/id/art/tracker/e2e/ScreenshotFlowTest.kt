package id.art.tracker.e2e

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.art.tracker.ARTracker
import id.art.tracker.MockIngest
import id.art.tracker.TestPlaygroundApplication
import id.art.tracker.privacy.artrackerMask
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Acceptance 2 of the screenshot design: on a real device (API 26+) a frame decoded from an uploaded `artracker-screen-v1`
 * chunk matches `UiAutomation.takeScreenshot()` of the same moment, at the frame's scale, within 8/255 per channel
 * outside the masked area (and the keyboard, which is not on screen here). JPEG 60 smears edges, so the comparison is on
 * a grid and requires 95 % of the samples to agree.
 *
 * Needs a device or emulator; it cannot run in the JVM unit tests.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ingest get() = TestPlaygroundApplication.ingest

    @Before
    fun clean() = ingest.clear()

    private fun screenChunks(seen: List<MockIngest.Recorded>): List<JSONObject> =
        seen.filter { it.method == "PUT" && it.path.substringBefore('?') == MockIngest.UPLOAD_PATH }
            .mapNotNull { r -> runCatching { JSONObject(r.body) }.getOrNull() }
            .filter { it.optString("format") == "artracker-screen-v1" }

    @Test
    fun uploadedFrameMatchesTheDeviceScreenshot_outsideMaskedAreas() {
        ingest.screenshotEnabled = true // the mock backend allows screenshots; the SDK reads it at the next replay start
        ARTracker.stopReplay()
        var maskBand: androidx.compose.ui.geometry.Rect? = null // where the masked box really is, in window pixels
        rule.setContent {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(160.dp).background(androidx.compose.ui.graphics.Color(0xFF1976D2)))
                Box(Modifier.fillMaxWidth().height(160.dp).background(androidx.compose.ui.graphics.Color(0xFFFFC107)))
                Box(
                    Modifier.fillMaxWidth().height(120.dp).onGloballyPositioned { maskBand = it.boundsInWindow() }
                        .artrackerMask().background(androidx.compose.ui.graphics.Color.Red),
                )
                Box(Modifier.fillMaxWidth().height(160.dp).background(androidx.compose.ui.graphics.Color(0xFF388E3C)))
            }
        }
        rule.waitForIdle()
        val startedAt = System.currentTimeMillis()
        ARTracker.startReplay()
        Thread.sleep(2_500) // config answered, first (forced) screenshot taken
        rule.waitForIdle()
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val shotAt = System.currentTimeMillis()
        Thread.sleep(1_000)
        ARTracker.stopReplay()
        ARTracker.flush()

        // Replay may already have been recording (an older chunk from stopReplay() above can arrive first), so wait for
        // the frame that was on screen when the device screenshot was taken: the newest one captured since the replay
        // started and not after the screenshot (the screen is static, so a later duplicate would never be sent anyway).
        fun frameOnScreen(seen: List<MockIngest.Recorded>): JSONObject? =
            screenChunks(seen)
                .flatMap { c -> (0 until c.getJSONArray("frames").length()).map { c.getJSONArray("frames").getJSONObject(it) } }
                .filter { it.getLong("ts") in startedAt..shotAt }
                .maxByOrNull { it.getLong("ts") }
        val seen = ingest.awaitUntil(20_000) { frameOnScreen(it) != null }
        val frame = checkNotNull(frameOnScreen(seen)) {
            "no frame captured between replay start ($startedAt) and the device screenshot ($shotAt); frame timestamps: ${
                screenChunks(seen).flatMap { c -> (0 until c.getJSONArray("frames").length()).map { c.getJSONArray("frames").getJSONObject(it).getLong("ts") } }
            }"
        }
        assertEquals("image/jpeg", frame.getString("mime"))
        val bytes = Base64.decode(frame.getString("data"), Base64.DEFAULT)
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Keep both images next to the app's files: a failure is easier to judge by eye (adb pull the folder).
        rule.activity.getExternalFilesDir(null)?.let { dir ->
            java.io.File(dir, "screenshot-frame.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            java.io.File(dir, "screenshot-device.png").outputStream().use { device.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val decor = rule.activity.window.decorView
        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
        val scale = image.width.toFloat() / decor.width
        // The system bars are drawn by SystemUI over the screen, not by the app window the SDK copies: leave them out.
        val bars = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())
        val barTop = (bars?.top ?: 0) + 4
        val barBottom = decor.height - (bars?.bottom ?: 0) - 4
        val band = checkNotNull(maskBand) { "the masked box was never laid out" }

        var checked = 0
        var agreed = 0
        val badRows = java.util.TreeMap<Int, Int>() // frame row -> disagreeing samples, for the failure message
        var y = 8
        while (y < image.height - 8) {
            var x = 8
            while (x < image.width - 8) {
                val windowY = y / scale
                val outsideMask = windowY < band.top - 4 || windowY > band.bottom + 4 // the masked band and its edges are skipped
                if (outsideMask && windowY > barTop && windowY < barBottom) {
                    val px = image.getPixel(x, y)
                    val dx = (origin[0] + x / scale).toInt().coerceIn(0, device.width - 1)
                    val dy = (origin[1] + y / scale).toInt().coerceIn(0, device.height - 1)
                    val ref = device.getPixel(dx, dy)
                    checked++
                    if (abs(Color.red(px) - Color.red(ref)) <= 8 && abs(Color.green(px) - Color.green(ref)) <= 8 &&
                        abs(Color.blue(px) - Color.blue(ref)) <= 8
                    ) {
                        agreed++
                    } else {
                        badRows.merge(y, 1, Int::plus)
                    }
                }
                x += 16
            }
            y += 16
        }
        assertTrue("too few samples: $checked", checked > 50)
        assertTrue(
            "only $agreed of $checked samples within 8/255; image ${image.width}x${image.height}, window ${decor.width}x${decor.height} " +
                "at ${origin.toList()}, device ${device.width}x${device.height}, disagreeing rows: $badRows",
            agreed >= checked * 0.95,
        )

        // And the masked band really is masked: no pure red left in it.
        val my = (((band.top + band.bottom) / 2) * scale).toInt().coerceIn(0, image.height - 1)
        val mp = image.getPixel(image.width / 2, my)
        assertTrue("the masked band must not show the app's red", !(Color.red(mp) > 200 && Color.green(mp) < 60))
    }
}
