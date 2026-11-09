package id.art.tracker.replay.capture

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.VectorDrawable
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
import org.robolectric.annotation.GraphicsMode

/**
 * Only small VECTOR icons are rasterized: bitmaps (a 40 dp avatar is a <= 64 px bitmap too), big images and anything
 * masked stay a placeholder. Real bitmaps (native graphics): the content hash needs the drawn pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconRasterizerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private var encodeCalls = 0
    private val encodedSizes = ArrayList<Pair<Int, Int>>()
    private val encoder: (Bitmap) -> IconRasterizer.Encoded? = {
        encodeCalls++
        encodedSizes += it.width to it.height
        IconRasterizer.Encoded("webp", byteArrayOf(1, 2, 3, 4))
    }

    /** Tests stand a bitmap in for a vector (a VectorDrawable cannot be built in code); the default rule is tested apart. */
    private fun rasterizer(budget: Int = 24) = IconRasterizer(encoder, budget, isIconSource = { it is BitmapDrawable })

    private fun walker(config: PrivacyConfig = PrivacyConfig(), budget: Int = 24) =
        ViewWalker(MaskingPolicy(config), icons = rasterizer(budget))

    private fun bitmapDrawable(w: Int, h: Int = w, color: Int = Color.WHITE) =
        BitmapDrawable(context.resources, Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })

    private fun layout(root: android.view.ViewGroup): android.view.ViewGroup {
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 1000)
        return root
    }

    private fun walk(w: ViewWalker, vararg views: View, width: Int = 24, height: Int = width): Wireframe {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                addView(it, LinearLayout.LayoutParams(width, height))
            }
        }
        return w.walk(layout(root))!!
    }

    private fun icon(d: android.graphics.drawable.Drawable = bitmapDrawable(24)) = ImageView(context).apply { setImageDrawable(d) }

    @Test
    fun smallIcon_becomesADataUri() {
        val wf = walk(walker(), icon()).children.single()
        assertEquals(WireframeType.IMAGE, wf.type)
        assertEquals("data:image/webp;base64,AQIDBA==", wf.visual!!.image)
        assertEquals(ImageFit.CONTAIN, wf.visual!!.imageFit)
        assertEquals(listOf(24 to 24), encodedSizes)
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
    fun bitmaps_areNeverIcons_byDefault() {
        val strict = ViewWalker(MaskingPolicy(PrivacyConfig()), icons = IconRasterizer(encoder))
        // A 24 px bitmap is exactly what an image loader produces for a 24 dp avatar.
        assertNull(walk(strict, icon(bitmapDrawable(24))).children.single().visual?.image)
        assertNull(walk(strict, icon(bitmapDrawable(200))).children.single().visual?.image)
        assertEquals(0, encodeCalls)
    }

    @Test
    fun defaultRule_acceptsVectorsOnly() {
        val vector = VectorDrawable::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        assertTrue(IconRasterizer.defaultIsIconSource(vector))
        assertFalse(IconRasterizer.defaultIsIconSource(bitmapDrawable(24)))
        assertFalse(IconRasterizer.defaultIsIconSource(GradientDrawable()))
    }

    @Test
    fun aBigIntrinsicSize_isNotAnIcon() {
        assertNull(walk(walker(), icon(bitmapDrawable(200)), width = 24).children.single().visual?.image)
        assertEquals(0, encodeCalls)
    }

    @Test
    fun theIconIsDrawnAtItsOwnSize_notStretchedToTheViewBox_andPaddingIsKept() {
        val view = icon(bitmapDrawable(24)).apply { setPadding(12, 12, 12, 12) }
        val wf = walk(walker(), view, width = 48).children.single()
        assertEquals(listOf(24 to 24), encodedSizes)
        assertEquals(listOf(12f, 12f, 12f, 12f), wf.visual!!.paddingDp)
    }

    @Test
    fun nonSquareBox_keepsTheIntrinsicAspect() {
        val wf = walk(walker(), icon(bitmapDrawable(24, 12)), width = 60, height = 30).children.single()
        assertEquals(listOf(24 to 12), encodedSizes)
        assertNotNull(wf.visual!!.image)
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
        val root = layout(LinearLayout(context).apply { addView(view, LinearLayout.LayoutParams(24, 24)) })
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
    fun sameLookingIconFromDifferentSources_isEncodedOnce() {
        // A list: every row owns its own drawable instance, but they all draw the same pixels.
        val rows = Array(5) { icon(bitmapDrawable(24)) }
        val children = walk(walker(), *rows).children
        assertEquals(5, children.count { it.visual?.image != null })
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
    fun perFrameEncodeBudget_limitsNewImages_andRecoversNextFrame() {
        val w = walker(budget = 3)
        val views = Array(8) { icon(bitmapDrawable(24, 24, Color.rgb(it * 20, 0, 0))) }
        val first = walk(w, *views).children
        assertEquals(3, first.count { it.visual?.image != null })
        val second = walk(w, *views).children
        assertEquals(6, second.count { it.visual?.image != null })
    }

    @Test
    fun encoderFailure_oversizedOrThrowing_leavesAPlaceholder() {
        val only = { d: android.graphics.drawable.Drawable -> d is BitmapDrawable }
        val failing = ViewWalker(MaskingPolicy(PrivacyConfig()), icons = IconRasterizer({ null }, isIconSource = only))
        assertNull(walk(failing, icon()).children.single().visual?.image)
        val throwing = ViewWalker(MaskingPolicy(PrivacyConfig()), icons = IconRasterizer({ error("boom") }, isIconSource = only))
        assertNull(walk(throwing, icon()).children.single().visual?.image)
        val huge = ViewWalker(MaskingPolicy(PrivacyConfig()), icons = IconRasterizer({ IconRasterizer.Encoded("png", ByteArray(100_000)) }, isIconSource = only))
        assertNull(walk(huge, icon()).children.single().visual?.image)
    }

    @Test
    fun compoundDrawables_becomeIconChildren() {
        val tv = TextView(context).apply {
            text = "Cart"
            val d = bitmapDrawable(24).apply { setBounds(0, 0, 24, 24) }
            setCompoundDrawables(d, null, null, null)
        }
        val wf = walk(walker(), tv, width = 100).children.single()
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
        assertTrue(walk(walker(), tv, width = 100).children.single().children.isEmpty())
        assertFalse(encodeCalls > 0)
    }
}
