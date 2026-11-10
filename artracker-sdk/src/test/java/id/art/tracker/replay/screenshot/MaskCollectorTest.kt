package id.art.tracker.replay.screenshot

import android.app.Activity
import android.app.Application
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.privacy.artrackerMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which screen regions must be painted over before a frame leaves the device (robolectric density 1.0: px == dp). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaskCollectorTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun collector(config: PrivacyConfig = PrivacyConfig(), compose: ComposeMaskApi? = null) =
        MaskCollector(MaskingPolicy(config), compose)

    /** A 400x800 column attached to a window (so screen positions are real); every child is 300x40, stacked from y = 0. */
    private fun screen(vararg children: View): ViewGroup {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (c in children) root.addView(c, LinearLayout.LayoutParams(300, 40))
        activity.setContentView(root)
        controller.start().postCreate(null).resume().visible()
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
        // Robolectric's shadow WebView does not lay itself out: give it its size directly.
        for (i in 0 until root.childCount) root.getChildAt(i).let { if (it is WebView) { it.right = 300; it.bottom = 40 } }
        return root
    }

    private fun rects(root: View, c: MaskCollector = collector()): List<MaskRect> {
        val out = ArrayList<MaskRect>()
        assertTrue("collection must succeed", c.collect(root, out))
        return out
    }

    private fun password() = EditText(context).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }

    @Test
    fun plainScreen_hasNothingToMask() {
        assertTrue(rects(screen(TextView(context).apply { text = "hello" }, ImageView(context))).isEmpty())
    }

    @Test
    fun passwordField_isMasked_atItsScreenRectangle() {
        val root = screen(TextView(context).apply { text = "label" }, password())
        val origin = IntArray(2).also { root.getLocationOnScreen(it) }
        assertEquals(MaskRect(origin[0], origin[1] + 40, origin[0] + 300, origin[1] + 80), rects(root).single())
    }

    @Test
    fun inputs_areMaskedByDefault_butNotWhenMaskAllInputsIsOff() {
        val edit = EditText(context)
        assertEquals(1, rects(screen(edit)).size)
        assertTrue(rects(screen(EditText(context)), collector(PrivacyConfig(maskAllInputs = false))).isEmpty())
    }

    @Test
    fun maskAllText_masksEveryTextView() {
        val root = screen(TextView(context).apply { text = "a" }, TextView(context).apply { text = "b" })
        assertTrue(rects(root).isEmpty())
        assertEquals(2, rects(root, collector(PrivacyConfig(maskAllText = true))).size)
    }

    @Test
    fun explicitMask_andSensitiveViewClasses_areMasked() {
        val tagged = View(context).apply { artrackerMask() }
        assertEquals(1, rects(screen(tagged)).size)
        class Secret(c: android.content.Context) : TextView(c)
        assertEquals(1, rects(screen(Secret(context)), collector(PrivacyConfig(sensitiveViewClasses = setOf(Secret::class.java)))).size)
    }

    @Test
    fun webView_isBlocked_andMasked() {
        assertEquals(1, rects(screen(WebView(context))).size)
    }

    @Test
    fun theChildrenOfAMaskedContainer_addNoRectsInsideIt() {
        val container = FrameLayout(context).apply {
            artrackerMask()
            addView(TextView(context).apply { text = "x" }, FrameLayout.LayoutParams(100, 20))
        }
        assertEquals(1, rects(screen(container)).size)
    }

    @Test
    fun aChildThatOverflowsAMaskedContainer_isMaskedToo() {
        val container = FrameLayout(context).apply {
            clipChildren = false
            artrackerMask()
            addView(TextView(context).apply { text = "x" }, FrameLayout.LayoutParams(100, 200)) // taller than the 40 px parent
        }
        val r = rects(screen(container))
        assertTrue(r.any { it.bottom >= 200 })
    }

    @Test
    fun invisibleAndZeroSizeViews_areSkipped() {
        val hidden = password().apply { visibility = View.INVISIBLE }
        val gone = password().apply { visibility = View.GONE }
        val transparent = password().apply { alpha = 0f }
        assertTrue(rects(screen(hidden, gone, transparent)).isEmpty())
    }

    @Test
    fun aMaskedRectIsClampedNowhere_butOffscreenOnesAreStillReturned() {
        // Scrolled-out content keeps its (off-screen) rect; painting it is a no-op on the bitmap.
        val root = screen(password())
        root.getChildAt(0).translationY = 5_000f
        assertEquals(1, rects(root).size)
    }

    @Test
    fun composeApi_isNotCalledForPlainViews() {
        val host = object : FrameLayout(context) {}
        val calls = ArrayList<Boolean>()
        val api = ComposeMaskApi { _, parentMasked, out -> calls += parentMasked; out += MaskRect(1, 2, 3, 4) }
        val root = screen(host)
        // Only views whose class chain names the Compose host are delegated.
        assertTrue(rects(root, collector(compose = api)).isEmpty() && calls.isEmpty())
    }

    @Test
    fun aThrowingViewFailsTheCollection_soTheFrameCanBeDropped() {
        val bad = object : View(context) {
            override fun getLocationOnScreen(outLocation: IntArray) = throw IllegalStateException("boom")
        }
        val root = screen(bad)
        bad.artrackerMask()
        assertFalse(collector().collect(root, ArrayList()))
    }
}
