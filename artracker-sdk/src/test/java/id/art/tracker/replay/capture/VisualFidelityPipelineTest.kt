package id.art.tracker.replay.capture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.PrivacyConfig
import id.art.tracker.core.Json
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.rrweb.RrwebEncoder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A real view tree, walked and encoded: what the web player receives carries the app's own look. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class VisualFidelityPipelineTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun viewTree_reachesRrwebWithColorsShapesFontsAndIcons() {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 12f; setStroke(1, 0xFFE2E8F0.toInt()) }
            elevation = 3f
            addView(
                TextView(context).apply {
                    text = "Checkout"; textSize = 16f; setTextColor(0xFF0F172A.toInt())
                    setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); gravity = Gravity.CENTER_HORIZONTAL
                },
                LinearLayout.LayoutParams(200, 24),
            )
            addView(
                ImageView(context).apply {
                    setImageDrawable(BitmapDrawable(context.resources, Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }))
                },
                LinearLayout.LayoutParams(24, 24),
            )
        }
        val root = LinearLayout(context).apply { addView(card, LinearLayout.LayoutParams(300, 120)) }
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 400)

        val wireframe = ViewWalker(MaskingPolicy(PrivacyConfig()), icons = IconRasterizer({ IconRasterizer.Encoded("png", byteArrayOf(1, 2, 3)) }, isIconSource = { it is BitmapDrawable })).walk(root)!!
        val json = Json.encode(RrwebEncoder().fullSnapshot(wireframe, 1L))

        assertTrue(json, json.contains("background-color:#ffffff"))
        assertTrue(json, json.contains("border-radius:12px"))
        assertTrue(json, json.contains("border:1px solid #e2e8f0"))
        assertTrue(json, json.contains("box-shadow:0px 3px 6px"))
        assertTrue(json, json.contains("color:#0f172a;font-size:16px;font-weight:700"))
        assertTrue(json, json.contains("justify-content:center"))
        assertTrue(json, json.contains("background-image:url(\\\"data:image/png;base64,AQID\\\")"))
    }
}
