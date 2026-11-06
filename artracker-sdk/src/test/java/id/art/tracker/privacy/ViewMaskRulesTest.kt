package id.art.tracker.privacy

import android.app.Application
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.PrivacyConfig
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import id.art.tracker.replay.model.toDebugString
import id.art.tracker.sdk.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewMaskRulesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val defaultPolicy = MaskingPolicy(PrivacyConfig())
    private val relaxedPolicy = MaskingPolicy(PrivacyConfig(maskAllInputs = false))

    private fun edit(inputType: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(context).apply { setInputType(inputType) }

    @Test
    fun passwordEditText_masked_evenWithUnmask() {
        val pw = edit(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pw.artrackerUnmask()
        assertTrue(ViewMaskRules.isPassword(pw))
        assertEquals(MaskDecision.MASK, defaultPolicy.forView(pw, parentMasked = false))
        assertEquals(MaskDecision.MASK, relaxedPolicy.forView(pw, parentMasked = false))
        for (variation in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )) {
            val v = edit(variation).also { it.artrackerUnmask() }
            assertTrue("variation $variation", ViewMaskRules.isPassword(v))
            assertEquals(MaskDecision.MASK, relaxedPolicy.forView(v, parentMasked = false))
        }
    }

    @Test
    fun passwordTransformationMethod_masked() {
        val tv = TextView(context).apply { transformationMethod = PasswordTransformationMethod.getInstance() }
        tv.artrackerUnmask()
        assertTrue(ViewMaskRules.isPassword(tv))
        assertEquals(MaskDecision.MASK, defaultPolicy.forView(tv, parentMasked = false))
        assertFalse(ViewMaskRules.isPassword(TextView(context)))
    }

    @Test
    fun numberAndPhone_masked() {
        val number = edit(InputType.TYPE_CLASS_NUMBER)
        val phone = edit(InputType.TYPE_CLASS_PHONE)
        assertTrue(ViewMaskRules.isSensitiveInput(number))
        assertTrue(ViewMaskRules.isSensitiveInput(phone))
        assertFalse(ViewMaskRules.isSensitiveInput(edit(InputType.TYPE_CLASS_TEXT)))
        assertEquals(MaskDecision.MASK, relaxedPolicy.forView(number, parentMasked = false))
        assertEquals(MaskDecision.MASK, relaxedPolicy.forView(phone, parentMasked = false))
    }

    @Test
    fun autofillCreditCard_masked() {
        for (hint in listOf(
            "creditCardNumber", "creditCardSecurityCode", "creditCardExpirationDate",
            "creditCardExpirationMonth", "phone", "emailAddress", "postalAddress", "smsOTPCode", "password",
        )) {
            val v = edit().apply { setAutofillHints(hint) }
            assertTrue(hint, ViewMaskRules.isSensitiveInput(v))
            assertEquals(hint, MaskDecision.MASK, relaxedPolicy.forView(v, parentMasked = false))
        }
        val benign = edit().apply { setAutofillHints("username") }
        assertFalse(ViewMaskRules.isSensitiveInput(benign))
        assertEquals(MaskDecision.NONE, relaxedPolicy.forView(benign, parentMasked = false))
    }

    @Test
    fun plainEditText_maskedWhenMaskAllInputs_trueByDefault() {
        assertEquals(MaskDecision.MASK, defaultPolicy.forView(edit(), parentMasked = false))
    }

    @Test
    fun plainEditText_notMaskedWhenMaskAllInputsFalse() {
        assertEquals(MaskDecision.NONE, relaxedPolicy.forView(edit(), parentMasked = false))
    }

    @Test
    fun xmlStringTag_masksChildrenViaParent() {
        val parent = FrameLayout(context).apply { tag = "artracker-mask" }
        val child = TextView(context)
        parent.addView(child)
        assertEquals(true, ViewMaskRules.explicitMask(parent))
        assertEquals(MaskDecision.MASK, defaultPolicy.forView(parent, parentMasked = false))
        // The walker propagates the parent's decision to children.
        assertEquals(MaskDecision.MASK, defaultPolicy.forView(child, parentMasked = true))
        assertEquals(MaskDecision.NONE, defaultPolicy.forView(child, parentMasked = false))
        assertEquals(false, ViewMaskRules.explicitMask(TextView(context).apply { tag = "artracker-unmask" }))
        assertEquals(null, ViewMaskRules.explicitMask(TextView(context).apply { tag = "other" }))
    }

    @Test
    fun resourceIdTag_and_extension_mask() {
        val byId = TextView(context).apply { setTag(R.id.artracker_mask, true) }
        val byExt = TextView(context).apply { artrackerMask() }
        assertEquals(true, ViewMaskRules.explicitMask(byId))
        assertEquals(true, ViewMaskRules.explicitMask(byExt))
        assertEquals(MaskDecision.MASK, defaultPolicy.forView(byId, parentMasked = false))
        assertEquals(MaskDecision.MASK, defaultPolicy.forView(byExt, parentMasked = false))
        val unmasked = TextView(context).apply { artrackerUnmask() }
        assertEquals(false, ViewMaskRules.explicitMask(unmasked))
        assertEquals(null, ViewMaskRules.explicitMask(TextView(context)))
    }

    @Test
    fun unmask_overridesMaskAllInputs_forNonPassword() {
        val plain = edit().apply { artrackerUnmask() }
        assertEquals(MaskDecision.NONE, defaultPolicy.forView(plain, parentMasked = false))
        // Explicit unmask also beats a masked parent and sensitive input class.
        assertEquals(MaskDecision.NONE, defaultPolicy.forView(plain, parentMasked = true))
        val number = edit(InputType.TYPE_CLASS_NUMBER).apply { artrackerUnmask() }
        assertEquals(MaskDecision.NONE, defaultPolicy.forView(number, parentMasked = false))
    }

    @Test
    fun sensitiveClass_masked() {
        class CardInputView(c: android.content.Context) : TextView(c)
        val policy = MaskingPolicy(PrivacyConfig(sensitiveViewClasses = setOf(CardInputView::class.java)))
        assertEquals(MaskDecision.MASK, policy.forView(CardInputView(context), parentMasked = false))
        assertEquals(MaskDecision.NONE, policy.forView(TextView(context), parentMasked = false))
    }

    @Test
    fun webView_blocked() {
        val web = WebView(context)
        assertTrue(ViewMaskRules.isBlocked(web))
        assertEquals(MaskDecision.BLOCK, defaultPolicy.forView(web, parentMasked = false))
        // Block wins over parent mask and explicit unmask/mask.
        assertEquals(MaskDecision.BLOCK, defaultPolicy.forView(web, parentMasked = true))
        web.artrackerUnmask()
        assertEquals(MaskDecision.BLOCK, defaultPolicy.forView(web, parentMasked = false))
        assertTrue(ViewMaskRules.isBlocked(android.view.SurfaceView(context)))
        assertTrue(ViewMaskRules.isBlocked(android.view.TextureView(context)))
        assertTrue(ViewMaskRules.isBlocked(android.widget.VideoView(context)))
        assertTrue(ViewMaskRules.isBlocked(MapView(context)))
        assertFalse(ViewMaskRules.isBlocked(TextView(context)))
    }

    private class MapView(c: android.content.Context) : View(c)

    @Test
    fun maskAllText_masksTextView() {
        val policy = MaskingPolicy(PrivacyConfig(maskAllInputs = false, maskAllText = true))
        assertEquals(MaskDecision.MASK, policy.forView(TextView(context), parentMasked = false))
        assertEquals(MaskDecision.NONE, defaultPolicy.forView(TextView(context), parentMasked = false))
        assertEquals(MaskDecision.NONE, policy.forView(View(context), parentMasked = false))
    }

    @Test
    fun maskText_lengthCappedAt32() {
        assertEquals("", MaskingPolicy.maskText(""))
        assertEquals("*****", MaskingPolicy.maskText("hello"))
        assertEquals("*".repeat(32), MaskingPolicy.maskText("x".repeat(32)))
        assertEquals("*".repeat(32), MaskingPolicy.maskText("x".repeat(500)))
        assertEquals("**", MaskingPolicy.maskText(StringBuilder("ab")))
    }

    @Test
    fun rulesThrowing_failsClosedToMask() {
        class Exploding(c: android.content.Context) : EditText(c) {
            var armed = false
            override fun getInputType(): Int = if (armed) throw IllegalStateException("boom") else super.getInputType()
        }
        val v = Exploding(context)
        v.armed = true
        assertEquals(MaskDecision.MASK, relaxedPolicy.forView(v, parentMasked = false))
        // Even with explicit unmask, a failing rule must not leak: password check throws first.
        v.artrackerUnmask()
        assertEquals(MaskDecision.MASK, relaxedPolicy.forView(v, parentMasked = false))
    }

    @Test
    fun wireframeDebugString_containsAllFieldsIncludingChildren() {
        val child = Wireframe(2, 1, 2, 3, 4, WireframeType.TEXT, text = "child-text", masked = false)
        val root = Wireframe(
            1, 0, 0, 10, 10, WireframeType.CONTAINER,
            textColor = 0x11, textSizeSp = 14f, background = 0x22, alpha = 0.5f,
            masked = true, summarized = true, children = listOf(child),
        )
        val s = root.toDebugString()
        assertTrue(s, "child-text" in s)
        assertTrue(s, "CONTAINER" in s && "masked=true" in s && "summarized=true" in s)
        assertFalse("secret" in s)
    }
}
