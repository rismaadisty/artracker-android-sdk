package id.art.tracker.replay.capture

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.privacy.artrackerMask
import id.art.tracker.replay.model.ImageFit
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only small icons are rasterized: photos, big images and anything masked stay a placeholder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IconRasterizerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private var encodeCalls = 0
    private val encoder: (Bitmap) -> IconRasterizer.Encoded? = {
        encodeCalls++
        IconRasterizer.Encoded("webp", byteArrayOf(1, 2, 3, 4))
    }

    private fun walker(config: PrivacyConfig = PrivacyConfig(), budget: Int = 24) =
        ViewWalker(MaskingPolicy(config), icons = IconRasterizer(encoder, budget))

    private fun bitmapDrawable(px: Int) = BitmapDrawable(context.resources, Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888))

    private fun walk(w: ViewWalker, vararg views: View, size: Int = 24): Wireframe {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                addView(it, LinearLayout.LayoutParams(size, size))
            }
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 1000)
        return w.walk(root)!!
    }

    private fun icon(d: android.graphics.drawable.Drawable = bitmapDrawable(24)) = ImageView(context).apply { setImageDrawable(d) }

    @Test
    fun smallIcon_becomesADataUri() {
        val wf = walk(walker(), icon()).children.single()
        assertEquals(WireframeType.IMAGE, wf.type)
        assertEquals("data:image/webp;base64,AQIDBA==", wf.visual!!.image)
        assertEquals(ImageFit.CONTAIN, wf.visual!!.imageFit)
    }

    @Test
    fun scaleTypes_mapToObjectFit() {
        fun fit(t: ImageView.ScaleType) = walk(walker(), icon().apply { scaleType = t }).children.single().visual!!.imageFit
        assertEquals(ImageFit.COVER, fit(ImageView.ScaleType.CENTER_CROP))
        assertEquals(ImageFit.FILL, fit(ImageView.ScaleType.FIT_XY))
        assertEquals(ImageFit.CONTAIN, fit(ImageView.ScaleType.FIT_CENTER))
        assertEquals(ImageFit.CONTAIN, fit(ImageView.ScaleType.CENTER_INSIDE))
    }

    @Test
    fun aBigBitmap_isNeverSent() {
        val wf = walk(walker(), icon(bitmapDrawable(200)), size = 24).children.single()
        assertNull(wf.visual?.image)
        assertEquals(0, encodeCalls)
    }

    @Test
    fun aSmallBitmapShownBig_isAPhotoNotAnIcon() {
        val wf = walk(walker(), icon(bitmapDrawable(24)), size = 120).children.single()
        assertNull(wf.visual?.image)
        assertEquals(0, encodeCalls)
    }

    @Test
    fun maskedImage_isNeverRasterized() {
        val masked = icon().apply { artrackerMask() }
        val wf = walk(walker(), masked).children.single()
        assertNull(wf.visual?.image)
        assertEquals(0, encodeCalls)
    }

    @Test
    fun theLiveDrawable_isNeverTouched() {
        val d = bitmapDrawable(24)
        val view = icon(d)
        val root = LinearLayout(context).apply { addView(view, LinearLayout.LayoutParams(24, 24)) }
        root.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 100, 100)
        d.setBounds(1, 2, 3, 4) // after the layout: whatever the walker does must leave it alone
        assertNotNull(walker().walk(root)!!.children.single().visual?.image)
        assertEquals(android.graphics.Rect(1, 2, 3, 4), d.bounds)
    }

    @Test
    fun sameIcon_isEncodedOnce_acrossFrames() {
        val w = walker()
        val view = icon()
        walk(w, view)
        walk(w, view)
        walk(w, view)
        assertEquals(1, encodeCalls)
    }

    @Test
    fun tintChange_isANewImage() {
        val w = walker()
        val view = icon()
        view.imageTintList = ColorStateList.valueOf(Color.RED)
        walk(w, view)
        view.imageTintList = ColorStateList.valueOf(Color.BLUE)
        walk(w, view)
        assertEquals(2, encodeCalls)
    }

    @Test
    fun perFrameBudget_limitsNewRasterizations_andRecoversNextFrame() {
        val w = walker(budget = 3)
        val views = List(8) { icon() }.toTypedArray()
        val first = walk(w, *views).children
        assertEquals(3, first.count { it.visual?.image != null })
        val second = walk(w, *views).children
        assertEquals(6, second.count { it.visual?.image != null })
    }

    @Test
    fun encoderFailure_oversizedOrThrowing_leavesAPlaceholder() {
        val failing = ViewWalker(MaskingPolicy(PrivacyConfig()), icons = IconRasterizer({ null }))
        assertNull(walk(failing, icon()).children.single().visual?.image)
        val throwing = ViewWalker(MaskingPolicy(PrivacyConfig()), icons = IconRasterizer({ error("boom") }))
        assertNull(walk(throwing, icon()).children.single().visual?.image)
        val huge = ViewWalker(MaskingPolicy(PrivacyConfig()), icons = IconRasterizer({ IconRasterizer.Encoded("png", ByteArray(100_000)) }))
        assertNull(walk(huge, icon()).children.single().visual?.image)
    }

    @Test
    fun compoundDrawables_becomeIconChildren() {
        val tv = TextView(context).apply {
            text = "Cart"
            val d = bitmapDrawable(24).apply { setBounds(0, 0, 24, 24) }
            setCompoundDrawables(d, null, null, null)
        }
        val wf = walk(walker(), tv, size = 100).children.single()
        val iconNode = wf.children.single()
        assertEquals(WireframeType.IMAGE, iconNode.type)
        assertNotNull(iconNode.visual!!.image)
        assertEquals(24, iconNode.width)
        assertEquals(wf.x, iconNode.x)
        assertTrue(iconNode.y >= wf.y && iconNode.y + iconNode.height <= wf.y + wf.height)
    }

    @Test
    fun maskedTextView_keepsNoIconChildren() {
        val tv = TextView(context).apply {
            text = "Cart"
            setCompoundDrawables(bitmapDrawable(24).apply { setBounds(0, 0, 24, 24) }, null, null, null)
            artrackerMask()
        }
        assertTrue(walk(walker(), tv, size = 100).children.single().children.isEmpty())
        assertFalse(encodeCalls > 0)
    }
}
