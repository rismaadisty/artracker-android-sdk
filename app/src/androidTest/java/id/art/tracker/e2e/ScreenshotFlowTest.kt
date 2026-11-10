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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        rule.setContent {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(160.dp).background(androidx.compose.ui.graphics.Color(0xFF1976D2)))
                Box(Modifier.fillMaxWidth().height(160.dp).background(androidx.compose.ui.graphics.Color(0xFFFFC107)))
                Box(Modifier.fillMaxWidth().height(120.dp).artrackerMask().background(androidx.compose.ui.graphics.Color.Red))
                Box(Modifier.fillMaxWidth().height(160.dp).background(androidx.compose.ui.graphics.Color(0xFF388E3C)))
            }
        }
        rule.waitForIdle()
        ARTracker.startReplay()
        Thread.sleep(2_500) // config answered, first (forced) screenshot taken
        rule.waitForIdle()
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        Thread.sleep(1_000)
        ARTracker.stopReplay()
        ARTracker.flush()

        val seen = ingest.awaitUntil(20_000) { screenChunks(it).isNotEmpty() }
        val chunks = screenChunks(seen)
        assertTrue("an artracker-screen-v1 chunk must be uploaded", chunks.isNotEmpty())
        val frames = chunks.last().getJSONArray("frames")
        val frame = frames.getJSONObject(frames.length() - 1)
        assertEquals("image/jpeg", frame.getString("mime"))
        val bytes = Base64.decode(frame.getString("data"), Base64.DEFAULT)
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val decor = rule.activity.window.decorView
        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
        val scale = image.width.toFloat() / decor.width
        val density = rule.activity.resources.displayMetrics.density
        val maskTopPx = (160 + 160) * density
        val maskBottomPx = maskTopPx + 120 * density

        var checked = 0
        var agreed = 0
        var y = 8
        while (y < image.height - 8) {
            var x = 8
            while (x < image.width - 8) {
                val windowY = y / scale
                if (windowY < maskTopPx - 4 || windowY > maskBottomPx + 4) { // skip the masked band and its edges
                    val px = image.getPixel(x, y)
                    val dx = (origin[0] + x / scale).toInt().coerceIn(0, device.width - 1)
                    val dy = (origin[1] + y / scale).toInt().coerceIn(0, device.height - 1)
                    val ref = device.getPixel(dx, dy)
                    checked++
                    if (abs(Color.red(px) - Color.red(ref)) <= 8 && abs(Color.green(px) - Color.green(ref)) <= 8 &&
                        abs(Color.blue(px) - Color.blue(ref)) <= 8
                    ) {
                        agreed++
                    }
                }
                x += 16
            }
            y += 16
        }
        assertTrue("too few samples: $checked", checked > 50)
        assertTrue("only $agreed of $checked samples within 8/255", agreed >= checked * 0.95)

        // And the masked band really is masked: no pure red left in it.
        val my = (((maskTopPx + maskBottomPx) / 2) * scale).toInt().coerceIn(0, image.height - 1)
        val mp = image.getPixel(image.width / 2, my)
        assertTrue("the masked band must not show the app's red", !(Color.red(mp) > 200 && Color.green(mp) < 60))
    }
}
