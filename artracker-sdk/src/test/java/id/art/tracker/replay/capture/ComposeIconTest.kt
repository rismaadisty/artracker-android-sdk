package id.art.tracker.replay.capture

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import id.art.tracker.replay.model.ImageFit
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.privacy.artrackerMask
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Icons drawn through a Compose `Painter` (`Icon`, `Image`) are rasterized; photos and masked nodes never are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real bitmaps: Compose's vector painter needs them
class ComposeIconTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var encodeCalls = 0
    private val encodedSizes = ArrayList<Pair<Int, Int>>()
    private val encoder: (Bitmap) -> IconRasterizer.Encoded? = {
        encodeCalls++
        encodedSizes += it.width to it.height
        IconRasterizer.Encoded("webp", byteArrayOf(9, 9, 9))
    }

    private fun host(): View {
        rule.waitForIdle()
        fun find(v: View): View? {
            if (v.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(rule.activity.window.decorView)!!
    }

    private fun capture(content: @Composable () -> Unit): Wireframe {
        rule.setContent(content)
        val icons = IconRasterizer(encoder)
        return ComposeWalker(MaskingPolicy(PrivacyConfig()), icons = icons).walk(host(), false)!!
    }

    private fun Wireframe.all(): List<Wireframe> = listOf(this) + children.flatMap { it.all() }

    private val star: ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f); lineTo(22f, 22f); lineTo(2f, 22f); close()
    }.build()

    @Test
    fun vectorIcon_withoutSemantics_becomesAnImageNode() {
        val wf = capture { Image(rememberVectorPainter(star), null, Modifier.size(24.dp)) }
        val img = wf.all().single { it.type == WireframeType.IMAGE }
        assertEquals("data:image/webp;base64,CQkJ", img.visual!!.image)
        assertEquals(24, img.width)
    }

    @Test
    fun colorFilterTint_isPartOfTheImage() {
        val wf = capture {
            Column {
                Image(rememberVectorPainter(star), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(Color.Red))
                Image(rememberVectorPainter(star), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(Color.Blue))
            }
        }
        assertEquals(2, wf.all().count { it.visual?.image != null })
        assertEquals(2, encodeCalls)
    }

    @Test
    fun vectorWithABigIntrinsicSize_isAPlaceholder() {
        val big = ImageVector.Builder(defaultWidth = 200.dp, defaultHeight = 200.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(fill = SolidColor(Color.Black)) { moveTo(0f, 0f); lineTo(24f, 0f); lineTo(24f, 24f); close() }.build()
        val wf = capture { Image(rememberVectorPainter(big), null, Modifier.size(100.dp)) }
        assertNull(wf.all().single { it.type == WireframeType.IMAGE }.visual?.image)
        assertEquals(0, encodeCalls)
    }

    @Test
    fun smallVectorShownBig_isStillAnIcon_drawnAtItsOwnSize() {
        val wf = capture { Image(rememberVectorPainter(star), null, Modifier.size(100.dp)) }
        assertNotNull(wf.all().single { it.type == WireframeType.IMAGE }.visual?.image)
        assertEquals(listOf(24 to 24), encodedSizes)
    }

    @Test
    fun bitmapPainters_areNeverIcons() {
        // A 24 px bitmap is what an image loader produces for a 24 dp avatar: pixels cannot tell an icon from a photo.
        val small = BitmapPainter(ImageBitmap(24, 24))
        val photo = BitmapPainter(ImageBitmap(300, 300))
        val wf = capture {
            Column {
                Image(small, null, Modifier.size(24.dp))
                Image(photo, null, Modifier.size(24.dp))
            }
        }
        assertEquals(2, wf.all().count { it.type == WireframeType.IMAGE })
        assertTrue(wf.all().none { it.visual?.image != null })
        assertEquals(0, encodeCalls)
    }

    @Test
    fun aClippedIcon_isDrawnAtItsFullSize_notAtTheVisibleSlice() {
        // At a list edge only part of the icon is visible; every scroll frame would otherwise be a new size.
        val wf = capture {
            Box(Modifier.size(12.dp).clipToBounds()) { Image(rememberVectorPainter(star), null, Modifier.size(24.dp)) }
        }
        assertTrue(wf.all().any { it.visual?.image != null })
        assertEquals(listOf(24 to 24), encodedSizes)
    }

    @Test
    fun iconInANonSquareBox_keepsItsAspect_andContentScaleSelectsTheFit() {
        val wf = capture {
            Column {
                Image(rememberVectorPainter(star), null, Modifier.size(60.dp, 30.dp))
                Image(rememberVectorPainter(star), null, Modifier.size(60.dp, 30.dp), contentScale = ContentScale.Crop)
                Image(rememberVectorPainter(star), null, Modifier.size(60.dp, 30.dp), contentScale = ContentScale.FillBounds)
            }
        }
        val images = wf.all().filter { it.type == WireframeType.IMAGE }
        assertEquals(listOf(ImageFit.CONTAIN, ImageFit.COVER, ImageFit.FILL), images.map { it.visual!!.imageFit })
        assertTrue(encodedSizes.all { it == 24 to 24 })
    }

    @Test
    fun iconsOfEveryListRow_shareOneEncode() {
        // Each LazyColumn item remembers its own painter instance: the cache must follow the pixels, not the instance.
        val wf = capture { Column { repeat(5) { Image(rememberVectorPainter(star), null, Modifier.size(24.dp)) } } }
        val uris = wf.all().mapNotNull { it.visual?.image }
        assertEquals(5, uris.size)
        assertEquals(1, uris.toSet().size)
        assertEquals(1, encodeCalls)
    }

    @Test
    fun unknownPainters_areNeverRasterized() {
        val custom = object : androidx.compose.ui.graphics.painter.Painter() {
            override val intrinsicSize = androidx.compose.ui.geometry.Size(24f, 24f)
            override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() = Unit
        }
        val wf = capture { Image(custom, null, Modifier.size(24.dp)) }
        assertNull(wf.all().single { it.type == WireframeType.IMAGE }.visual?.image)
        assertEquals(0, encodeCalls)
    }

    @Test
    fun maskedSubtree_neverRasterizes() {
        val wf = capture { Box(Modifier.artrackerMask()) { Image(rememberVectorPainter(star), null, Modifier.size(24.dp)) } }
        assertTrue(wf.all().none { it.visual?.image != null })
        assertEquals(0, encodeCalls)
    }

    @Test
    fun imageWithContentDescription_getsTheIcon_butNotTheDescription() {
        val wf = capture { Image(rememberVectorPainter(star), "acct-4111-desc", Modifier.size(24.dp)) }
        val img = wf.all().single { it.type == WireframeType.IMAGE }
        assertNotNull(img.visual?.image)
        assertTrue(!wf.toString().contains("acct-4111-desc"))
    }

    @Test
    fun colorPainter_isRasterized() {
        val wf = capture { Image(ColorPainter(Color.Green), null, Modifier.size(24.dp)) }
        assertNotNull(wf.all().single { it.type == WireframeType.IMAGE }.visual?.image)
    }

    @Test
    fun realEncoder_producesADecodableImage_withTheTintDrawn() {
        rule.setContent {
            Image(rememberVectorPainter(star), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(Color.Red))
        }
        val wf = ComposeWalker(MaskingPolicy(PrivacyConfig()), icons = IconRasterizer()).walk(host(), false)!!
        val uri = wf.all().single { it.type == WireframeType.IMAGE }.visual!!.image!!
        assertTrue(uri, uri.startsWith("data:image/webp;base64,") || uri.startsWith("data:image/png;base64,"))
        val bytes = android.util.Base64.decode(uri.substringAfter("base64,"), android.util.Base64.DEFAULT)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(24, bmp.width)
        assertEquals(24, bmp.height)
        assertEquals("inside the triangle: opaque red", android.graphics.Color.RED, bmp.getPixel(12, 18))
        assertEquals("outside the triangle: transparent", 0, android.graphics.Color.alpha(bmp.getPixel(1, 1)))
        assertTrue("an icon is a few hundred bytes", bytes.size < IconRasterizer.MAX_BYTES)
    }
}
