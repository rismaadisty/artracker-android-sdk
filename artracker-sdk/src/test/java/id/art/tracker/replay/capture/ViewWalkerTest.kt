package id.art.tracker.replay.capture

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.privacy.artrackerMask
import id.art.tracker.privacy.artrackerUnmask
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import id.art.tracker.replay.model.toDebugString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewWalkerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val walker = ViewWalker(MaskingPolicy(PrivacyConfig()))

    private class TestActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            super.onCreate(savedInstanceState)
        }
    }

    /** Measures and lays out [root] as a 1000x1000 px root at (0, 0). */
    private fun layoutRoot(root: ViewGroup): ViewGroup {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 1000)
        return root
    }

    private fun lp() = LinearLayout.LayoutParams(100, 20)

    private fun text(s: String?) = TextView(context).apply {
        text = s
        layoutParams = lp()
    }

    private fun linear(vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it) }
    }

    private fun Wireframe.all(): List<Wireframe> = listOf(this) + children.flatMap { it.all() }

    // ---- brief tests ----

    @Test
    fun textAndPassword() {
        val password = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("secret")
            layoutParams = lp()
        }
        val root = layoutRoot(linear(text("Hello"), password))

        val wf = walker.walk(root)!!

        assertEquals(2, wf.children.size)
        val hello = wf.children[0]
        assertEquals(WireframeType.TEXT, hello.type)
        assertEquals("Hello", hello.text)
        assertFalse(hello.masked)
        val input = wf.children[1]
        assertEquals(WireframeType.INPUT, input.type)
        assertEquals("******", input.text)
        assertTrue(input.masked)
        assertFalse(wf.toDebugString().contains("secret"))
    }

    @Test
    fun goneView_excluded() {
        val gone = text("gone-text").apply { visibility = View.GONE }
        val invisible = text("invisible-text").apply { visibility = View.INVISIBLE }
        val transparent = text("transparent-text").apply { alpha = 0f }
        val zero = text("zero-text").apply { layoutParams = LinearLayout.LayoutParams(0, 0) }
        val goneParent = linear(text("hidden-child")).apply {
            visibility = View.GONE
            layoutParams = lp()
        }
        val root = layoutRoot(linear(text("shown"), gone, invisible, transparent, zero, goneParent))

        val wf = walker.walk(root)!!

        assertEquals(listOf("shown"), wf.children.map { it.text })
        val dump = wf.toDebugString()
        for (leaked in listOf("gone-text", "invisible-text", "transparent-text", "zero-text", "hidden-child")) {
            assertFalse(leaked, dump.contains(leaked))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "xhdpi")
    fun boundsInDp() {
        val controller = Robolectric.buildActivity(TestActivity::class.java).create()
        val activity = controller.get()
        val child = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(200, 100).apply {
                leftMargin = 20
                topMargin = 40
            }
        }
        val container = FrameLayout(activity)
        container.addView(child)
        activity.setContentView(container)
        controller.start().postCreate(null).resume().visible()
        val density = activity.resources.displayMetrics.density
        assertEquals(2f, density, 0f)
        // Pin down where the content is on screen so the assertion below is absolute.
        val origin = IntArray(2)
        container.getLocationOnScreen(origin)
        assertEquals(0, origin[0])
        assertEquals(0, origin[1])
        val onScreen = IntArray(2)
        child.getLocationOnScreen(onScreen)
        assertEquals(20, onScreen[0])
        assertEquals(40, onScreen[1])

        val wf = ViewWalker(MaskingPolicy(PrivacyConfig())).walk(container)!!

        val node = wf.children.single()
        assertEquals(10, node.x)
        assertEquals(20, node.y)
        assertEquals(100, node.width)
        assertEquals(50, node.height)
    }

    @Test
    fun over2000Nodes_isCappedWithSummary() {
        val root = FrameLayout(context)
        repeat(2500) {
            root.addView(TextView(context).apply { text = "t$it" }, FrameLayout.LayoutParams(10, 10))
        }
        layoutRoot(root)

        val wf = walker.walk(root)!!

        val nodes = wf.all()
        assertTrue("size=${nodes.size}", nodes.size <= 2001)
        assertEquals(1, nodes.count { it.summarized })
        assertEquals(2000, nodes.count { !it.summarized })
        val summary = nodes.single { it.summarized }
        assertTrue(summary.children.isEmpty())
        assertNull(summary.text)
    }

    @Test
    fun depthBeyondMax_isSummarized() {
        var inner: ViewGroup = FrameLayout(context)
        val top = inner
        repeat(60) {
            val next = FrameLayout(context)
            inner.addView(next, FrameLayout.LayoutParams(900, 900))
            inner = next
        }
        inner.addView(text("deep-text"), FrameLayout.LayoutParams(10, 10))
        layoutRoot(top)

        val wf = ViewWalker(MaskingPolicy(PrivacyConfig()), maxDepth = 50).walk(top)!!

        val nodes = wf.all()
        assertTrue(nodes.any { it.summarized })
        assertTrue(nodes.size <= 52)
        assertFalse(wf.toDebugString().contains("deep-text"))
    }

    @Test
    fun imageView_hasNoBitmapData() {
        var drawableReads = 0
        val image = object : ImageView(context) {
            override fun getDrawable(): Drawable? {
                drawableReads++
                return super.getDrawable()
            }
        }.apply {
            setImageDrawable(BitmapDrawable(context.resources, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)))
            contentDescription = "photo-description"
            layoutParams = lp()
        }
        val root = layoutRoot(linear(image))
        drawableReads = 0

        val wf = walker.walk(root)!!

        val node = wf.children.single()
        assertEquals(WireframeType.IMAGE, node.type)
        assertNull(node.text)
        assertNull(node.textColor)
        assertNull(node.textSizeSp)
        assertNull(node.background)
        assertTrue(node.children.isEmpty())
        assertEquals(0, drawableReads)
        assertFalse(wf.toDebugString().contains("photo-description"))
    }

    @Test
    fun rootViewLocator_fallsBackToDecorView() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        val roots = RootViewLocator.roots(activity)

        assertEquals(listOf(activity.window.decorView), roots)
    }

    @Test
    fun rootViewLocator_noFallback_isEmptyAndNeverThrows() {
        assertTrue(RootViewLocator.roots(null).isEmpty())
    }

    @Test
    fun rootViewLocator_visibleActivity_includesDecorViewOnly() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val roots = RootViewLocator.roots(activity)

        assertTrue(roots.contains(activity.window.decorView))
        assertTrue(roots.all { it.isAttachedToWindow && it.visibility == View.VISIBLE })
    }

    // ---- walker contract tests ----

    private class MapView(c: android.content.Context) : FrameLayout(c)

    @Test
    fun blockedWebView_hasNoChildrenOrText() {
        val map = MapView(context).apply {
            layoutParams = LinearLayout.LayoutParams(300, 300)
            addView(TextView(context).apply { text = "leak" }, FrameLayout.LayoutParams(50, 50))
        }
        val root = layoutRoot(linear(map))

        val wf = walker.walk(root)!!

        val node = wf.children.single()
        assertEquals(WireframeType.SURFACE, node.type)
        assertTrue(node.masked)
        assertNull(node.text)
        assertTrue(node.children.isEmpty())
        assertFalse(wf.toDebugString().contains("leak"))
    }

    @Test
    fun blockedWebView_realWebView_isWebNodeWithoutChildren() {
        // The shadow WebView does not lay itself out, so report a size directly.
        val web = WebView(context).apply { layoutParams = LinearLayout.LayoutParams(300, 300) }
        val root = layoutRoot(linear(web))
        web.right = 300
        web.bottom = 300
        assertEquals(300, web.width)

        val wf = walker.walk(root)!!

        val node = wf.children.single()
        assertEquals(WireframeType.WEB, node.type)
        assertTrue(node.masked)
        assertNull(node.text)
        assertTrue(node.children.isEmpty())
    }

    @Test
    fun passwordTextViewNamedMapView_isStillBlocked() {
        // Policy answers MASK (password wins) but the view is a blocked class: never treat as plain text.
        class PasswordMapView(c: android.content.Context) : TextView(c)
        val v = PasswordMapView(context).apply {
            transformationMethod = PasswordTransformationMethod.getInstance()
            text = "secret"
            layoutParams = lp()
        }
        val root = layoutRoot(linear(v))

        val wf = walker.walk(root)!!

        val node = wf.children.single()
        assertEquals(WireframeType.SURFACE, node.type)
        assertTrue(node.masked)
        assertNull(node.text)
        assertFalse(wf.toDebugString().contains("secret"))
    }

    @Test
    fun maskedContainer_masksDescendantsViaParentMasked() {
        val card = TextView(context).apply {
            text = "card 4111"
            layoutParams = FrameLayout.LayoutParams(50, 50)
        }
        val container = FrameLayout(context).apply {
            artrackerMask()
            layoutParams = lp()
            addView(card)
        }
        val root = layoutRoot(linear(container))

        val wf = walker.walk(root)!!

        val masked = wf.children.single()
        assertTrue(masked.masked)
        assertNull(masked.text)
        val child = masked.children.single()
        assertTrue(child.masked)
        assertEquals("*********", child.text)
        val dump = wf.toDebugString()
        assertFalse(dump.contains("card 4111"))
        assertFalse(dump.contains("4111"))
    }

    @Test
    fun unmaskedParent_doesNotMaskChildren() {
        val plain = text("plain-text")
        val parent = linear(plain).apply { layoutParams = lp() }
        // masked > unmasked > text: the unmasked node resets parentMasked for its own children.
        val inner = text("inner-text")
        val unmasked = linear(inner).apply {
            artrackerUnmask()
            layoutParams = lp()
        }
        val masked = linear(unmasked).apply {
            artrackerMask()
            layoutParams = lp()
        }
        val root = layoutRoot(linear(parent, masked))

        val wf = walker.walk(root)!!

        val parentNode = wf.children[0]
        assertFalse(parentNode.masked)
        assertEquals("plain-text", parentNode.children.single().text)
        assertFalse(parentNode.children.single().masked)
        val maskedNode = wf.children[1]
        assertTrue(maskedNode.masked)
        val unmaskedNode = maskedNode.children.single()
        assertFalse(unmaskedNode.masked)
        assertEquals("inner-text", unmaskedNode.children.single().text)
        assertFalse(unmaskedNode.children.single().masked)
    }

    @Test
    fun contentDescriptionAndHint_neverCaptured() {
        val tv = TextView(context).apply {
            text = "visible"
            hint = "hint-secret"
            contentDescription = "desc-secret"
            tag = "tag-secret"
            layoutParams = lp()
        }
        val edit = EditText(context).apply {
            hint = "edit-hint-secret"
            contentDescription = "edit-desc-secret"
            tag = "edit-tag-secret"
            layoutParams = lp()
        }
        val maskedContainer = FrameLayout(context).apply {
            artrackerMask()
            contentDescription = "container-desc-secret"
            layoutParams = lp()
        }
        val root = layoutRoot(linear(tv, edit, maskedContainer))

        val wf = walker.walk(root)!!

        assertEquals("visible", wf.children[0].text)
        assertTrue(wf.children[1].text.isNullOrEmpty())
        assertFalse(wf.toDebugString().contains("secret"))
    }

    private class ExplodingText(c: android.content.Context) : TextView(c) {
        var armed = false
        override fun getText(): CharSequence = if (armed) error("boom") else super.getText()
    }

    @Test
    fun viewThrowingOnGetText_failsClosed() {
        val bad = ExplodingText(context).apply {
            text = "raw-secret"
            layoutParams = lp()
        }
        val root = layoutRoot(linear(text("before"), bad, text("after")))
        bad.armed = true

        val wf = walker.walk(root)!!

        assertEquals(3, wf.children.size)
        val node = wf.children[1]
        assertTrue(node.masked)
        assertNull(node.text)
        assertTrue(node.children.isEmpty())
        assertEquals("before", wf.children[0].text)
        assertEquals("after", wf.children[2].text)
        assertFalse(wf.toDebugString().contains("raw-secret"))
    }

    private class NullText(c: android.content.Context) : TextView(c) {
        override fun getText(): CharSequence? = null
    }

    @Test
    fun nullText_nullGuard() {
        val view = NullText(context).apply { layoutParams = lp() }
        val root = layoutRoot(linear(view))

        val unmasked = walker.walk(root)!!.children.single()
        assertNull(unmasked.text)
        assertFalse(unmasked.masked)

        val maskAll = ViewWalker(MaskingPolicy(PrivacyConfig(maskAllText = true)))
        val masked = maskAll.walk(root)!!.children.single()
        assertNull(masked.text)
        assertTrue(masked.masked)
    }

    @Test
    fun longText_isCappedAt200() {
        val root = layoutRoot(linear(text("a".repeat(500))))

        val node = walker.walk(root)!!.children.single()

        assertEquals(200, node.text!!.length)
    }

    @Test
    fun maskAllText_masksPlainTextWithStars() {
        val root = layoutRoot(linear(text("Hello world")))

        val node = ViewWalker(MaskingPolicy(PrivacyConfig(maskAllText = true))).walk(root)!!.children.single()

        assertTrue(node.masked)
        assertEquals("***********", node.text)
    }

    @Test
    fun styleAndTypes_areCaptured() {
        val label = text("label").apply {
            setTextColor(Color.RED)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(Color.BLUE)
            alpha = 0.5f
        }
        val button = android.widget.Button(context).apply {
            text = "Go"
            layoutParams = lp()
        }
        val plainBg = View(context).apply {
            background = object : Drawable() {
                override fun draw(canvas: android.graphics.Canvas) = Unit
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

                @Deprecated("Deprecated in Java")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            layoutParams = lp()
        }
        val root = layoutRoot(linear(label, button, plainBg))

        val wf = walker.walk(root)!!

        val l = wf.children[0]
        assertEquals(Color.RED, l.textColor)
        assertEquals(14f, l.textSizeSp!!, 0.01f)
        assertEquals(Color.BLUE, l.background)
        assertEquals(0.5f, l.alpha, 0.001f)
        assertEquals(WireframeType.BUTTON, wf.children[1].type)
        assertEquals(WireframeType.CONTAINER, wf.children[2].type)
        assertNull(wf.children[2].background)
        assertEquals(System.identityHashCode(label), l.id)
    }

    @Test
    fun composeHost_isDelegatedToComposeWalker_withParentMaskedOnlyWhenMasked() {
        val seen = mutableListOf<Pair<String, Boolean>>()
        val compose = ComposeWalkerApi { view, parentMasked ->
            seen += view.javaClass.name to parentMasked
            Wireframe(1, 0, 0, 1, 1, WireframeType.COMPOSE_BLOCK)
        }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        activity.setContent { Text("hello") }
        controller.start().postCreate(null).resume().visible()
        val decor = activity.window.decorView as ViewGroup
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 500, 500)

        val wf = ViewWalker(MaskingPolicy(PrivacyConfig()), composeWalker = compose).walk(decor)

        assertNotNull(wf)
        assertEquals(listOf("androidx.compose.ui.platform.AndroidComposeView" to false), seen)
        assertTrue(wf!!.all().any { it.type == WireframeType.COMPOSE_BLOCK })

        // Same host under a masked ancestor: parentMasked = true is passed down.
        seen.clear()
        decor.artrackerMask()
        ViewWalker(MaskingPolicy(PrivacyConfig()), composeWalker = compose).walk(decor)
        assertEquals(listOf("androidx.compose.ui.platform.AndroidComposeView" to true), seen)

        // Without a compose walker the host is a plain view and nothing is delegated.
        seen.clear()
        decor.artrackerUnmask()
        val plain = ViewWalker(MaskingPolicy(PrivacyConfig()), composeWalker = null).walk(decor)
        assertTrue(seen.isEmpty())
        assertFalse(plain!!.all().any { it.type == WireframeType.COMPOSE_BLOCK })
    }

    @Test
    fun composeWalkerThrowing_failsClosed() {
        val compose = ComposeWalkerApi { _, _ -> error("compose boom") }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        activity.setContent { Text("compose-secret") }
        controller.start().postCreate(null).resume().visible()
        val decor = activity.window.decorView as ViewGroup
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 500, 500)

        val wf = ViewWalker(MaskingPolicy(PrivacyConfig()), composeWalker = compose).walk(decor)

        assertNotNull(wf)
        assertTrue(wf!!.all().any { it.masked && it.children.isEmpty() && it.text == null })
        assertFalse(wf.toDebugString().contains("compose-secret"))
    }
}
