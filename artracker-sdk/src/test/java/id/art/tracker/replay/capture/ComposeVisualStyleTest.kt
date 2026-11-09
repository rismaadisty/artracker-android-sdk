package id.art.tracker.replay.capture

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.model.TextAlignment
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import id.art.tracker.replay.model.toDebugString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** App colors, shapes, borders, shadow and typography read from Compose modifiers and text layouts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeVisualStyleTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun host(): View {
        rule.waitForIdle()
        fun find(v: View): View? {
            if (v.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(rule.activity.window.decorView)!!
    }

    private fun capture(config: PrivacyConfig = PrivacyConfig(), content: @Composable () -> Unit): Wireframe {
        rule.setContent(content)
        return ComposeWalker(MaskingPolicy(config)).walk(host(), false)!!
    }

    private fun Wireframe.all(): List<Wireframe> = listOf(this) + children.flatMap { it.all() }

    /** A box with semantics so it is a node of the semantics tree; [style] adds the modifiers under test. */
    private fun Modifier.node(): Modifier = semantics { role = Role.Button }

    private fun Wireframe.button() = all().single { it.type == WireframeType.BUTTON }

    @Test
    fun background_colorAndRoundedShape() {
        val wf = capture {
            Box(Modifier.size(40.dp).node().background(Color(0xFF6750A4), RoundedCornerShape(12.dp)))
        }
        val b = wf.button()
        assertEquals(0xFF6750A4.toInt(), b.background)
        assertEquals(listOf(12f, 12f, 12f, 12f), b.visual!!.radiiDp)
    }

    @Test
    fun circleShape_hasHalfTheSizeAsRadius() {
        val wf = capture { Box(Modifier.size(40.dp).node().background(Color.Red, CircleShape)) }
        assertEquals(listOf(20f, 20f, 20f, 20f), wf.button().visual!!.radiiDp)
    }

    @Test
    fun perCornerShape_keepsCssCornerOrder() {
        val wf = capture {
            Box(Modifier.size(40.dp).node().background(Color.Red, RoundedCornerShape(topStart = 4.dp, topEnd = 8.dp, bottomEnd = 0.dp, bottomStart = 12.dp)))
        }
        assertEquals(listOf(4f, 8f, 0f, 12f), wf.button().visual!!.radiiDp)
    }

    @Test
    fun translucentBackground_keepsItsAlpha() {
        val wf = capture { Box(Modifier.size(40.dp).node().background(Color.White.copy(alpha = 0.5f))) }
        assertEquals(0x80FFFFFF.toInt(), wf.button().background)
    }

    @Test
    fun linearGradient_becomesCssGradient() {
        val wf = capture {
            Box(Modifier.size(40.dp).node().background(Brush.verticalGradient(listOf(Color.Red, Color.Blue))))
        }
        val b = wf.button()
        assertEquals("linear-gradient(180deg,#ff0000,#0000ff)", b.visual!!.gradient)
        assertNull(b.background)
    }

    @Test
    fun horizontalGradient_is90Degrees() {
        val wf = capture {
            Box(Modifier.size(40.dp).node().background(Brush.horizontalGradient(listOf(Color.Black, Color.White))))
        }
        assertEquals("linear-gradient(90deg,#000000,#ffffff)", wf.button().visual!!.gradient)
    }

    @Test
    fun border_widthColorAndShape() {
        val wf = capture {
            Box(Modifier.size(40.dp).node().border(2.dp, Color.Blue, RoundedCornerShape(8.dp)))
        }
        val v = wf.button().visual!!
        assertEquals(2f, v.borderWidthDp!!, 0.01f)
        assertEquals(0xFF0000FF.toInt(), v.borderColor)
        assertEquals(listOf(8f, 8f, 8f, 8f), v.radiiDp)
    }

    @Test
    fun shadowAndClip_andAlpha() {
        val wf = capture {
            Box(Modifier.size(40.dp).node().alpha(0.5f).shadow(4.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)))
        }
        val b = wf.button()
        assertEquals(4f, b.visual!!.shadowDp!!, 0.01f)
        assertTrue(b.visual!!.clip)
        assertEquals(0.5f, b.alpha, 0.01f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "xhdpi")
    fun lengthsAreDividedByDensity() {
        val wf = capture {
            Box(Modifier.size(40.dp).node().background(Color.Red, RoundedCornerShape(12.dp)).border(2.dp, Color.Blue, RoundedCornerShape(12.dp)))
        }
        val v = wf.button().visual!!
        assertEquals(listOf(12f, 12f, 12f, 12f), v.radiiDp)
        assertEquals(2f, v.borderWidthDp!!, 0.01f)
    }

    @Test
    fun plainNode_hasNoVisualStyle() {
        val wf = capture { Box(Modifier.size(40.dp).node()) }
        assertNull(wf.button().visual)
    }

    @Test
    fun text_fullTypography() {
        val wf = capture {
            Text(
                "Hello", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Serif, color = Color(0xFF112233), textAlign = TextAlign.Center,
                modifier = Modifier.width(200.dp),
            )
        }
        val t = wf.all().single { it.type == WireframeType.TEXT }
        assertEquals(18f, t.textSizeSp)
        assertEquals(600, t.fontWeight)
        assertEquals(0xFF112233.toInt(), t.textColor)
        assertTrue(t.visual!!.italic)
        assertEquals("serif", t.visual!!.fontFamily)
        assertEquals(TextAlignment.CENTER, t.visual!!.textAlign)
        assertFalse(t.visual!!.wrap)
    }

    @Test
    fun text_defaults_carryNoOverrides() {
        val wf = capture { Text("plain") }
        val t = wf.all().single { it.type == WireframeType.TEXT }
        assertNull(t.fontWeight)
        assertNull(t.visual?.fontFamily)
        assertNull(t.visual?.textAlign)
        assertFalse(t.visual?.italic ?: false)
    }

    @Test
    fun text_multiLine_wrapsWithLineHeight() {
        val wf = capture {
            Column { Text("one\ntwo\nthree", lineHeight = 20.sp, modifier = Modifier.fillMaxWidth()) }
        }
        val t = wf.all().single { it.type == WireframeType.TEXT }
        assertTrue(t.visual!!.wrap)
        assertEquals(20f, t.visual!!.lineHeightDp!!, 0.01f)
    }

    @Test
    fun maskedText_keepsStyle_neverTheString() {
        val wf = capture(PrivacyConfig(maskAllText = true)) { Text("secret-value", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        val t = wf.all().single { it.type == WireframeType.TEXT }
        assertEquals(16f, t.textSizeSp)
        assertEquals(700, t.fontWeight)
        assertFalse(wf.toDebugString().contains("secret-value"))
        assertNotNull(t.text)
    }

    // ---- nodes with no semantics (a Box with Modifier.background, an Icon without contentDescription) ----

    @Test
    fun boxWithBackground_withoutSemantics_isStillCaptured() {
        val wf = capture {
            Column {
                Box(Modifier.size(40.dp).background(Color.Red, RoundedCornerShape(12.dp)))
                Text("after")
            }
        }
        val red = wf.all().single { it.background == 0xFFFF0000.toInt() }
        assertEquals(WireframeType.CONTAINER, red.type)
        assertEquals(40, red.width)
        assertEquals(listOf(12f, 12f, 12f, 12f), red.visual!!.radiiDp)
        assertEquals(1, wf.all().count { it.type == WireframeType.TEXT })
    }

    @Test
    fun boxWithoutAnyVisual_addsNoNode() {
        val wf = capture { Column { Box(Modifier.size(40.dp)); Text("only") } }
        assertTrue(wf.all().none { it.width == 40 && it.height == 40 })
    }

    @Test
    fun nestedBackgrounds_keepBothAndTheTextOnce() {
        val wf = capture {
            Box(Modifier.size(100.dp).background(Color.Blue)) {
                Box(Modifier.size(50.dp).background(Color.Green)) { Text("inner") }
            }
        }
        assertEquals(1, wf.all().count { it.background == 0xFF0000FF.toInt() })
        assertEquals(1, wf.all().count { it.background == 0xFF00FF00.toInt() })
        assertEquals(1, wf.all().count { it.type == WireframeType.TEXT })
    }

    @Test
    fun semanticNodes_areNeverDuplicatedByTheLayoutWalk() {
        val wf = capture { Box(Modifier.size(40.dp).node().background(Color.Red)) }
        assertEquals(1, wf.all().count { it.background == 0xFFFF0000.toInt() })
    }

    @Test
    fun backgroundIsPaintedBeforeTheContentOnTopOfIt() {
        val wf = capture { Box(Modifier.size(100.dp).background(Color.Blue)) { Text("on top") } }
        val ids = wf.all().map { it.type }
        assertTrue(ids.indexOf(WireframeType.CONTAINER) >= 0)
        val blue = wf.all().first { it.background == 0xFF0000FF.toInt() }
        val text = wf.all().first { it.type == WireframeType.TEXT }
        assertTrue(wf.all().indexOf(blue) < wf.all().indexOf(text))
    }

    @Test
    fun layoutOnlyNodes_countAgainstTheNodeBudget() {
        rule.setContent { Column { repeat(30) { Box(Modifier.size(10.dp).background(Color.Red)) } } }
        val all = ComposeWalker(MaskingPolicy(PrivacyConfig()), maxNodes = 10).walk(host(), false)!!.all()
        assertTrue("size=${all.size}", all.size <= 12)
        assertTrue(all.any { it.summarized })
    }
}
