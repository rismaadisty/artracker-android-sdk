package id.art.tracker.e2e

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.art.tracker.ARTracker
import id.art.tracker.MockIngest
import id.art.tracker.TestPlaygroundApplication
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The visual part of the plan's manual checklist (acceptance 3), as far as a machine can judge it, on a real device:
 * fonts, icons, a photo-like image, colors and a dialog with its dim are compared pixel by pixel (grid, 8/255, 93 % of
 * the samples) between an uploaded screenshot frame and `UiAutomation.takeScreenshot()` of the same moment. What a
 * person still has to look at: motion, bottom sheets, real photos and the dashboard itself.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ingest get() = TestPlaygroundApplication.ingest

    @Before
    fun clean() = ingest.clear()

    private fun screenChunks(seen: List<MockIngest.Recorded>): List<JSONObject> =
        seen.filter { it.method == "PUT" && it.path.substringBefore('?') == MockIngest.UPLOAD_PATH }
            .mapNotNull { r -> runCatching { JSONObject(r.body) }.getOrNull() }
            .filter { it.optString("format") == "artracker-screen-v1" }

    /** A colourful, photo-like picture: smooth gradient sky, a sun, hills (real bitmap content, not a flat fill). */
    private fun picture(): Bitmap {
        val b = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, 240f, Color.rgb(30, 120, 220), Color.rgb(250, 190, 120), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 480f, 240f, p)
        p.shader = null
        p.color = Color.rgb(255, 235, 120); c.drawCircle(380f, 70f, 40f, p)
        p.color = Color.rgb(40, 120, 60); c.drawCircle(120f, 300f, 160f, p)
        p.color = Color.rgb(25, 90, 45); c.drawCircle(360f, 330f, 170f, p)
        return b
    }

    private fun compareFrameWithDevice(withDialog: Boolean, expectedMinAgreement: Double) {
        ingest.screenshotEnabled = true
        ARTracker.stopReplay()
        val photo = picture().asImageBitmap()
        rule.setContent {
            Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White).padding(12.dp)) {
                Text("Judul besar", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Teks miring monospace", fontSize = 16.sp, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Monospace)
                Text("Teks serif biasa", fontSize = 18.sp, fontFamily = FontFamily.Serif)
                Row(Modifier.padding(vertical = 8.dp)) {
                    Icon(Icons.Default.Home, contentDescription = null, Modifier.size(40.dp), tint = androidx.compose.ui.graphics.Color(0xFF1976D2))
                    Icon(Icons.Default.Favorite, contentDescription = null, Modifier.size(40.dp), tint = androidx.compose.ui.graphics.Color(0xFFD32F2F))
                }
                Image(photo, contentDescription = null, Modifier.fillMaxWidth().height(180.dp))
                Box(Modifier.fillMaxWidth().height(48.dp).background(androidx.compose.ui.graphics.Color(0xFF388E3C)))
            }
            if (withDialog) {
                Dialog(onDismissRequest = {}) {
                    Box(Modifier.size(260.dp, 140.dp).background(androidx.compose.ui.graphics.Color.White).padding(16.dp)) {
                        Text("Dialog di atas layar", fontSize = 20.sp)
                    }
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(500)
        val startedAt = System.currentTimeMillis()
        ARTracker.startReplay()
        Thread.sleep(2_500)
        rule.waitForIdle()
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val shotAt = System.currentTimeMillis()
        Thread.sleep(1_000)
        ARTracker.stopReplay()
        ARTracker.flush()

        fun frameOnScreen(seen: List<MockIngest.Recorded>): JSONObject? =
            screenChunks(seen)
                .flatMap { c -> (0 until c.getJSONArray("frames").length()).map { c.getJSONArray("frames").getJSONObject(it) } }
                .filter { it.getLong("ts") in startedAt..shotAt }
                .maxByOrNull { it.getLong("ts") }
        val seen = ingest.awaitUntil(20_000) { frameOnScreen(it) != null }
        val frame = checkNotNull(frameOnScreen(seen)) { "no frame between $startedAt and $shotAt" }
        val bytes = Base64.decode(frame.getString("data"), Base64.DEFAULT)
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        rule.activity.getExternalFilesDir(null)?.let { dir ->
            val tag = if (withDialog) "dialog" else "base"
            java.io.File(dir, "screens-$tag-frame.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            java.io.File(dir, "screens-$tag-device.png").outputStream().use { device.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        val decor = rule.activity.window.decorView
        val scale = image.width.toFloat() / decor.width
        val bars = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())
        val barTop = (bars?.top ?: 0) + 4
        val barBottom = decor.height - (bars?.bottom ?: 0) - 4
        var checked = 0
        var agreed = 0
        var y = 8
        while (y < image.height - 8) {
            var x = 8
            while (x < image.width - 8) {
                val windowY = y / scale
                if (windowY > barTop && windowY < barBottom) {
                    val px = image.getPixel(x, y)
                    val ref = device.getPixel((x / scale).toInt().coerceIn(0, device.width - 1), (y / scale).toInt().coerceIn(0, device.height - 1))
                    checked++
                    if (abs(Color.red(px) - Color.red(ref)) <= 8 && abs(Color.green(px) - Color.green(ref)) <= 8 &&
                        abs(Color.blue(px) - Color.blue(ref)) <= 8
                    ) agreed++
                }
                x += 12
            }
            y += 12
        }
        assertTrue("too few samples: $checked", checked > 200)
        assertTrue(
            "only $agreed of $checked samples (${"%.1f".format(agreed * 100.0 / checked)} %) within 8/255; need ${expectedMinAgreement * 100} %",
            agreed >= checked * expectedMinAgreement,
        )
    }

    @Test
    fun fontsIconsPicturesAndColors_matchTheDevice() = compareFrameWithDevice(withDialog = false, expectedMinAgreement = 0.93)

    @Test
    fun aDialogWithItsDim_matchesTheDevice() = compareFrameWithDevice(withDialog = true, expectedMinAgreement = 0.93)
}
