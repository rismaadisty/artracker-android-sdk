package id.art.tracker.replay.capture

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.shape.MaterialShapeDrawable
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.model.TextAlignment
import id.art.tracker.replay.model.Wireframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real app colors, shapes, elevation and typography read from XML views (robolectric density is 1.0: px == dp). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewVisualStyleTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun walk(view: View, config: PrivacyConfig = PrivacyConfig()): Wireframe {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(view, LinearLayout.LayoutParams(300, 40))
        }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 1000)
        return ViewWalker(MaskingPolicy(config)).walk(root)!!.children.single()
    }

    private fun box(bg: android.graphics.drawable.Drawable?) = View(context).apply { background = bg }

    @Test
    fun colorDrawable_isTheBackground() {
        assertEquals(Color.RED, walk(box(ColorDrawable(Color.RED))).background)
    }

    @Test
    fun backgroundTint_overridesAColorDrawable() {
        val v = box(ColorDrawable(Color.RED)).apply { backgroundTintList = ColorStateList.valueOf(Color.BLUE) }
        assertEquals(Color.BLUE, walk(v).background)
    }

    @Test
    fun gradientDrawable_solidWithPerCornerRadii() {
        val d = GradientDrawable().apply {
            setColor(Color.RED)
            cornerRadii = floatArrayOf(4f, 4f, 8f, 8f, 0f, 0f, 12f, 12f)
        }
        val wf = walk(box(d))
        assertEquals(Color.RED, wf.background)
        assertEquals(listOf(4f, 8f, 0f, 12f), wf.visual!!.radiiDp)
    }

    @Test
    fun gradientDrawable_gradientStrokeAndUniformRadius() {
        val d = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.RED, Color.BLUE)).apply {
            cornerRadius = 6f
            setStroke(2, Color.GREEN)
        }
        val v = walk(box(d)).visual!!
        assertEquals("linear-gradient(180deg,#ff0000,#0000ff)", v.gradient)
        assertEquals(listOf(6f, 6f, 6f, 6f), v.radiiDp)
        assertEquals(2f, v.borderWidthDp!!, 0.01f)
        assertEquals(Color.GREEN, v.borderColor)
    }

    @Test
    fun gradientOrientations_mapToCssAngles() {
        fun css(o: GradientDrawable.Orientation) =
            walk(box(GradientDrawable(o, intArrayOf(Color.BLACK, Color.WHITE)))).visual!!.gradient
        assertEquals("linear-gradient(90deg,#000000,#ffffff)", css(GradientDrawable.Orientation.LEFT_RIGHT))
        assertEquals("linear-gradient(0deg,#000000,#ffffff)", css(GradientDrawable.Orientation.BOTTOM_TOP))
        assertEquals("linear-gradient(135deg,#000000,#ffffff)", css(GradientDrawable.Orientation.TL_BR))
    }

    @Test
    fun translucentColors_useRgba() {
        val d = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x80FF0000.toInt(), Color.BLUE))
        assertEquals("linear-gradient(180deg,rgba(255,0,0,0.502),#0000ff)", walk(box(d)).visual!!.gradient)
    }

    @Test
    fun wrappers_areUnwrappedToTheShapeBehindThem() {
        val shape = GradientDrawable().apply { setColor(Color.RED); cornerRadius = 6f }
        val ripple = RippleDrawable(ColorStateList.valueOf(Color.WHITE), shape, null)
        assertEquals(Color.RED, walk(box(ripple)).background)

        val states = StateListDrawable().apply { addState(intArrayOf(), shape) }
        assertEquals(Color.RED, walk(box(states)).background)

        val layers = LayerDrawable(arrayOf(shape, ColorDrawable(Color.GREEN)))
        assertEquals(Color.RED, walk(box(layers)).background)

        val inset = InsetDrawable(shape, 4)
        assertEquals(Color.RED, walk(box(inset)).background)
        assertEquals(listOf(6f, 6f, 6f, 6f), walk(box(InsetDrawable(ripple, 4))).visual!!.radiiDp)
    }

    @Test
    fun materialShapeDrawable_isReadByName() {
        val d = MaterialShapeDrawable(
            ColorStateList.valueOf(0xFF6750A4.toInt()), ColorStateList.valueOf(Color.GRAY), 3f, 20f, 20f, 20f, 20f,
        )
        val wf = walk(box(RippleDrawable(ColorStateList.valueOf(Color.WHITE), d, null)))
        assertEquals(0xFF6750A4.toInt(), wf.background)
        assertEquals(listOf(20f, 20f, 20f, 20f), wf.visual!!.radiiDp)
        assertEquals(3f, wf.visual!!.borderWidthDp!!, 0.01f)
        assertEquals(Color.GRAY, wf.visual!!.borderColor)
    }

    @Test
    fun elevationBecomesAShadow_andClipToOutlineClips() {
        val v = box(ColorDrawable(Color.WHITE)).apply { elevation = 4f; clipToOutline = true }
        val visual = walk(v).visual!!
        assertEquals(4f, visual.shadowDp!!, 0.01f)
        assertTrue(visual.clip)
    }

    @Test
    fun plainView_hasNoVisualStyle() {
        assertNull(walk(View(context)).visual)
    }

    @Test
    fun text_boldItalicCentered() {
        val tv = TextView(context).apply {
            text = "Jalankan"
            setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val wf = walk(tv)
        assertEquals(700, wf.fontWeight)
        assertTrue(wf.visual!!.italic)
        assertEquals(TextAlignment.CENTER, wf.visual!!.textAlign)
        assertFalse(wf.visual!!.wrap)
    }

    @Test
    fun text_endGravity_isRight() {
        val tv = TextView(context).apply { text = "x"; gravity = Gravity.END }
        assertEquals(TextAlignment.RIGHT, walk(tv).visual!!.textAlign)
    }

    @Test
    fun text_default_hasNoVisualOverrides() {
        val wf = walk(TextView(context).apply { text = "plain" })
        assertNull(wf.fontWeight)
        assertNull(wf.visual)
    }

    @Test
    fun text_multiLine_wrapsWithItsLineHeight() {
        val tv = TextView(context).apply {
            text = "one\ntwo\nthree"
            textSize = 14f
        }
        val root = LinearLayout(context).apply { addView(tv, LinearLayout.LayoutParams(60, 100)) }
        root.measure(View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 60, 100)
        val wf = ViewWalker(MaskingPolicy(PrivacyConfig())).walk(root)!!.children.single()
        assertTrue(wf.visual!!.wrap)
        assertTrue(wf.visual!!.lineHeightDp!! > 0f)
    }

    @Test
    fun hint_isShownWhenEmptyAndNotMasked_butNeverWhenMasked() {
        val open = EditText(context).apply { hint = "Search products"; setTextColor(Color.BLACK); setHintTextColor(Color.LTGRAY) }
        val shown = walk(open, PrivacyConfig(maskAllInputs = false))
        assertEquals("Search products", shown.text)
        assertEquals(Color.LTGRAY, shown.textColor)

        val masked = walk(EditText(context).apply { hint = "Search products" })
        assertFalse(masked.text.orEmpty().contains("Search"))
    }

    @Test
    fun maskedText_stillCarriesStyleButNotTheString() {
        val tv = TextView(context).apply { text = "secret-value"; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(Color.RED) }
        val wf = walk(tv, PrivacyConfig(maskAllText = true))
        assertEquals(Color.RED, wf.textColor)
        assertEquals(700, wf.fontWeight)
        assertFalse(wf.text!!.contains("secret"))
    }
}
