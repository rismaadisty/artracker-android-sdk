package id.art.tracker.replay.capture

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeWalkerTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun walker(config: PrivacyConfig = PrivacyConfig(), maxNodes: Int = 2000) =
        ComposeWalker(MaskingPolicy(config), maxNodes = maxNodes)

    private fun host(): View {
        rule.waitForIdle()
        return findHost(rule.activity.window.decorView)!!
    }

    private fun findHost(v: View): View? {
        if (v.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findHost(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun capture(config: PrivacyConfig = PrivacyConfig(), content: @Composable () -> Unit): Wireframe {
        rule.setContent(content)
        return walker(config).walk(host(), false)!!
    }

    private fun Wireframe.all(): List<Wireframe> = listOf(this) + children.flatMap { it.all() }

    @Test
    fun text_isCaptured() {
        val wf = capture { Text("Hello") }
        val node = wf.all().single { it.type == WireframeType.TEXT }
        assertEquals("Hello", node.text)
        assertFalse(node.masked)
        assertTrue(node.width > 0 && node.height > 0)
    }

    @Test
    fun longText_isCappedAt200() {
        val wf = capture { Text("x".repeat(500)) }
        assertEquals(200, wf.all().single { it.type == WireframeType.TEXT }.text!!.length)
    }

    @Test
    fun passwordSemantics_masked() {
        val wf = capture(PrivacyConfig(maskAllInputs = false)) {
            Box(
                Modifier.size(50.dp).semantics {
                    password()
                    editableText = AnnotatedString("secret")
                },
            )
        }
        val node = wf.all().single { it.type == WireframeType.INPUT }
        assertTrue(node.masked)
        assertEquals("******", node.text)
        assertFalse(wf.toDebugString().contains("secret"))
    }

    @Test
    fun password_isNotUnmaskable() {
        val wf = capture {
            Box(
                Modifier.size(50.dp).artrackerUnmask().semantics {
                    password()
                    editableText = AnnotatedString("secret")
                },
            )
        }
        assertFalse(wf.toDebugString().contains("secret"))
        assertTrue(wf.all().single { it.type == WireframeType.INPUT }.masked)
    }

    @Test
    fun artrackerMaskModifier_masksSubtree() {
        val wf = capture {
            Column(Modifier.artrackerMask()) {
                Text("Rp 1.000.000")
                Box(Modifier.size(20.dp)) { Text("nested-secret") }
            }
        }
        val debug = wf.toDebugString()
        assertFalse(debug.contains("Rp 1.000.000"))
        assertFalse(debug.contains("nested-secret"))
        val texts = wf.all().filter { it.type == WireframeType.TEXT }
        assertEquals(2, texts.size)
        assertTrue(texts.all { it.masked })
        assertTrue(texts.any { it.text == "*".repeat("Rp 1.000.000".length) })
    }

    @Test
    fun maskIsScopedToSubtree_siblingsStayVisible() {
        val wf = capture {
            Column {
                Column(Modifier.artrackerMask()) { Text("hidden-one") }
                Text("visible-one")
            }
        }
        val debug = wf.toDebugString()
        assertFalse(debug.contains("hidden-one"))
        assertTrue(debug.contains("visible-one"))
    }

    @Test
    fun maskModifierFirstOnSameNode_staysMasked() {
        // Peer semantics modifiers on one node collapse first-wins (Compose does not use the merge policy there).
        val wf = capture {
            Column(Modifier.artrackerMask().artrackerUnmask()) { Text("both-modifiers") }
        }
        assertFalse(wf.toDebugString().contains("both-modifiers"))
    }

    @Test
    fun unmaskInsideMaskedSubtree_followsViewRules() {
        val wf = capture {
            Column(Modifier.artrackerMask()) {
                Text("still-hidden")
                Text("opted-out", Modifier.artrackerUnmask())
            }
        }
        val debug = wf.toDebugString()
        assertFalse(debug.contains("still-hidden"))
        assertTrue(debug.contains("opted-out"))
    }

    @Test
    fun textField_maskedByDefault() {
        val wf = capture { BasicTextField(value = "mypin1234", onValueChange = {}) }
        assertFalse(wf.toDebugString().contains("mypin1234"))
        val node = wf.all().single { it.type == WireframeType.INPUT }
        assertTrue(node.masked)
        assertEquals("*********", node.text)
    }

    @Test
    fun textField_capturedWhenMaskAllInputsOff() {
        val wf = capture(PrivacyConfig(maskAllInputs = false)) {
            BasicTextField(value = "plain-value", onValueChange = {})
        }
        val node = wf.all().single { it.type == WireframeType.INPUT }
        assertFalse(node.masked)
        assertEquals("plain-value", node.text)
    }

    @Test
    fun artrackerUnmask_beatsMaskAllInputs_forNonPassword() {
        val wf = capture {
            BasicTextField(value = "public-name", onValueChange = {}, modifier = Modifier.artrackerUnmask())
        }
        val node = wf.all().single { it.type == WireframeType.INPUT }
        assertFalse(node.masked)
        assertEquals("public-name", node.text)
    }

    @Test
    fun maskAllText_masksText() {
        val wf = capture(PrivacyConfig(maskAllText = true)) { Text("all-text-secret") }
        assertFalse(wf.toDebugString().contains("all-text-secret"))
        assertTrue(wf.all().single { it.type == WireframeType.TEXT }.masked)
    }

    @Test
    fun unmask_beatsMaskAllText() {
        val wf = capture(PrivacyConfig(maskAllText = true)) {
            Text("shown-text", Modifier.artrackerUnmask())
        }
        assertEquals("shown-text", wf.all().single { it.type == WireframeType.TEXT }.text)
    }

    @Test
    fun contentDescription_neverCaptured() {
        val wf = capture {
            Column {
                Box(Modifier.size(20.dp).semantics { contentDescription = "acct-4111-desc" })
                Box(
                    Modifier.size(20.dp).semantics {
                        role = Role.Image
                        contentDescription = "photo-of-card-desc"
                    },
                )
            }
        }
        val debug = wf.toDebugString()
        assertFalse(debug.contains("acct-4111-desc"))
        assertFalse(debug.contains("photo-of-card-desc"))
        assertEquals(2, wf.all().count { it.type == WireframeType.IMAGE })
    }

    @Test
    fun roleButton_isButton() {
        val wf = capture {
            Box(Modifier.size(20.dp).semantics { role = Role.Button })
        }
        assertEquals(1, wf.all().count { it.type == WireframeType.BUTTON })
    }

    @Test
    fun offscreenNodes_areSkipped() {
        // Bounds are clipped to the visible area, so items scrolled out of view have zero size and are dropped.
        rule.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { repeat(2500) { Text("t$it") } } }
        val all = walker().walk(host(), false)!!.all()
        assertTrue("size=${all.size}", all.size < 200)
        assertTrue(all.none { it.summarized })
    }

    @Test
    fun nodeCap_summarizes() {
        val rule2 = walker(maxNodes = 100)
        rule.setContent { Box { repeat(2500) { Box(Modifier.size(10.dp).semantics { }) } } }
        val wf = rule2.walk(host(), false)!!
        val all = wf.all()
        assertTrue("size=${all.size}", all.size <= 101)
        assertTrue("size=${all.size}", all.any { it.summarized })
    }

    @Test
    fun nodeCap_defaultIs2000() {
        rule.setContent { Box { repeat(2500) { Box(Modifier.size(10.dp).semantics { }) } } }
        val all = walker().walk(host(), false)!!.all()
        assertTrue("size=${all.size}", all.size <= 2001)
        assertTrue("size=${all.size}", all.any { it.summarized })
    }

    @Test
    fun deepNesting_isDepthCapped() {
        rule.setContent { Nest(60) }
        val wf = walker().walk(host(), false)!!
        assertTrue(wf.all().any { it.summarized })
        assertTrue(wf.all().size <= 55)
    }

    @Composable
    private fun Nest(n: Int) {
        if (n == 0) Text("leaf") else Box(Modifier.size(100.dp).semantics { }) { Nest(n - 1) }
    }

    @Test
    fun coordinates_areScreenRelativeDp() {
        val wf = capture { Box(Modifier.size(50.dp).semantics { role = Role.Button }) }
        val host = host()
        val density = host.resources.displayMetrics.density
        val loc = IntArray(2)
        host.getLocationOnScreen(loc)
        val button = wf.all().single { it.type == WireframeType.BUTTON }
        assertEquals(50, button.width)
        assertEquals(50, button.height)
        assertTrue(button.x >= Math.round(loc[0] / density))
        assertTrue(button.y >= Math.round(loc[1] / density))
    }

    @Test
    fun reflectionFailure_yieldsComposeBlock() {
        rule.setContent { Text("never-seen") }
        val host = host()
        val w = ComposeWalker(MaskingPolicy(PrivacyConfig()), SemanticsAccess { throw IllegalStateException("boom") })
            .walk(host, false)!!
        assertEquals(WireframeType.COMPOSE_BLOCK, w.type)
        assertTrue(w.masked)
        assertTrue(w.children.isEmpty())
        assertEquals(null, w.text)
        assertTrue(w.width > 0 && w.height > 0)
        assertFalse(w.toDebugString().contains("never-seen"))
    }

    @Test
    fun nullRoot_yieldsComposeBlock() {
        rule.setContent { Text("never-seen") }
        val w = ComposeWalker(MaskingPolicy(PrivacyConfig()), SemanticsAccess { null }).walk(host(), false)!!
        assertEquals(WireframeType.COMPOSE_BLOCK, w.type)
        assertTrue(w.masked)
    }

    @Test
    fun nonComposeView_yieldsComposeBlock_neverThrows() {
        rule.setContent { Text("x") }
        val plain = View(rule.activity)
        val w = walker().walk(plain, false)!!
        assertEquals(WireframeType.COMPOSE_BLOCK, w.type)
        assertTrue(w.masked)
    }

    @Test
    fun parentMasked_masksAllComposeText() {
        rule.setContent { Column { Text("under-masked-host"); Box(Modifier.size(10.dp).artrackerUnmask()) { Text("also-under") } } }
        val wf = walker().walk(host(), true)!!
        val debug = wf.toDebugString()
        assertFalse(debug.contains("under-masked-host"))
        assertTrue(wf.masked)
    }

    @Test
    fun viewWalkerIntegration_capturesComposeTextAndMasksSecrets() {
        rule.setContent {
            Column {
                Text("Hello integration")
                Column(Modifier.artrackerMask()) { Text("Rp 9.999") }
                BasicTextField(value = "pin-7788", onValueChange = {})
            }
        }
        rule.waitForIdle()
        val decor = rule.activity.window.decorView as ViewGroup
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1000, 1000)
        rule.waitForIdle()
        val policy = MaskingPolicy(PrivacyConfig())
        val wf = ViewWalker(policy, composeWalker = ComposeWalker(policy)).walk(decor)
        assertNotNull(wf)
        val all = wf!!.all()
        assertTrue(all.none { it.type == WireframeType.COMPOSE_BLOCK })
        assertTrue(all.any { it.type == WireframeType.TEXT && it.text == "Hello integration" && !it.masked })
        assertTrue(all.any { it.type == WireframeType.TEXT && it.text == "********" && it.masked })
        assertTrue(all.any { it.type == WireframeType.INPUT && it.masked })
        val debug = wf.toDebugString()
        assertFalse(debug.contains("Rp 9.999"))
        assertFalse(debug.contains("pin-7788"))
    }
}
