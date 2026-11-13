package id.art.tracker.replay.screenshot

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.EditText
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.privacy.artrackerMask
import id.art.tracker.privacy.artrackerUnmask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The semantics-tree pass that finds Compose regions to paint over; no styles are read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeMaskCollectorTest {
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

    private fun collect(config: PrivacyConfig = PrivacyConfig(), content: @androidx.compose.runtime.Composable () -> Unit): List<MaskRect> {
        rule.setContent(content)
        val out = ArrayList<MaskRect>()
        ComposeMaskCollector(MaskingPolicy(config)).collect(host(), false, out)
        return out
    }

    @Test
    fun plainContent_hasNothingToMask() {
        assertTrue(collect { Column { Text("hello"); Box(Modifier.size(20.dp)) } }.isEmpty())
    }

    @Test
    fun passwordNode_isMasked_withItsBounds() {
        val r = collect { Box(Modifier.size(50.dp).semantics { password(); text = AnnotatedString("secret") }) }
        assertEquals(1, r.size)
        assertTrue(r.single().right - r.single().left > 0 && r.single().bottom - r.single().top > 0)
    }

    @Test
    fun explicitMask_masksTheSubtree() {
        assertTrue(collect { Box(Modifier.size(40.dp).artrackerMask()) { Text("inside") } }.isNotEmpty())
    }

    @Test
    fun explicitUnmask_optsOutOfMaskAllText() {
        val r = collect(PrivacyConfig(maskAllText = true)) {
            Column { Text("masked by policy"); Text("shown", Modifier.artrackerUnmask()) }
        }
        assertEquals(1, r.size) // only the first Text
    }

    @Test
    fun maskAllText_masksEveryText() {
        val r = collect(PrivacyConfig(maskAllText = true)) { Column { Text("a"); Text("b") } }
        assertEquals(2, r.size)
    }

    @Test
    fun anUnreadableTree_masksTheWholeHost() {
        rule.setContent { Box(Modifier.size(30.dp)) }
        val view = host()
        val out = ArrayList<MaskRect>()
        ComposeMaskCollector(MaskingPolicy(PrivacyConfig()), semanticsAccess = SemanticsAccess { null }).collect(view, false, out)
        assertEquals(1, out.size)
    }

    @Test
    fun aFocusedComposeTextField_masksOnlyTheField_notTheWholeHost() {
        rule.setContent {
            Column {
                BasicTextField(value = "typed", onValueChange = {}, modifier = Modifier.size(100.dp, 30.dp).testTag("field"))
                Text("plain label")
                Box(Modifier.size(200.dp))
            }
        }
        rule.onNodeWithTag("field").performClick() // focus: the host view then reports itself as a text editor
        rule.waitForIdle()
        val view = host()
        assertTrue("precondition: the focused Compose host reports onCheckIsTextEditor()", view.onCheckIsTextEditor())

        val policy = MaskingPolicy(PrivacyConfig())
        val out = ArrayList<MaskRect>()
        assertTrue(MaskCollector(policy, ComposeMaskCollectorFactory.create(policy)).collect(rule.activity.window.decorView, out))

        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        val hostRect = MaskRect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        assertTrue("the text field must still be masked, got $out", out.isNotEmpty())
        assertTrue("the whole Compose host must not be masked, got $out", out.none { it.contains(hostRect) })
    }

    @Test
    fun aViewEmbeddedWithAndroidView_isMaskedByTheViewRules_notSkipped() {
        rule.setContent {
            AndroidView(factory = { ctx -> EditText(ctx) }, modifier = Modifier.size(120.dp, 40.dp))
        }
        rule.waitForIdle()
        val policy = MaskingPolicy(PrivacyConfig())
        val out = ArrayList<MaskRect>()
        val ok = MaskCollector(policy, ComposeMaskCollectorFactory.create(policy)).collect(rule.activity.window.decorView, out)
        assertTrue(ok)
        assertTrue("the embedded EditText must get a mask rect, got $out", out.isNotEmpty())
    }
}
