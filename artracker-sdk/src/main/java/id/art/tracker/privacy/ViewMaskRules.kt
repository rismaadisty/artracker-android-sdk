package id.art.tracker.privacy

import android.os.Build
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import android.widget.VideoView
import id.art.tracker.sdk.R

/** Stateless view classification used by [MaskingPolicy]. */
internal object ViewMaskRules {
    internal const val TAG_MASK = "artracker-mask"
    internal const val TAG_UNMASK = "artracker-unmask"

    private val PASSWORD_INPUT_TYPES = intArrayOf(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
    )

    // View.AUTOFILL_HINT_* values (API 26; SMS OTP is API 28). Literals keep minSdk 24 safe.
    private val SENSITIVE_AUTOFILL_HINTS = setOf(
        "password", "creditCardNumber", "creditCardSecurityCode",
        "phone", "emailAddress", "postalAddress", "smsOTPCode",
    )
    private const val CREDIT_CARD_EXPIRATION_PREFIX = "creditCardExpiration"

    fun isPassword(v: View): Boolean {
        if (v !is TextView) return false
        val type = v.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return PASSWORD_INPUT_TYPES.contains(type) || v.transformationMethod is PasswordTransformationMethod
    }

    fun isSensitiveInput(v: View): Boolean {
        if (v is TextView) {
            val cls = v.inputType and InputType.TYPE_MASK_CLASS
            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hints = v.autofillHints ?: return false
            return hints.any { it in SENSITIVE_AUTOFILL_HINTS || it.startsWith(CREDIT_CARD_EXPIRATION_PREFIX) }
        }
        return false
    }

    /** `true` = mask, `false` = unmask, `null` = no explicit setting. The resource-id tag wins over the string tag. */
    fun explicitMask(v: View): Boolean? {
        (v.getTag(R.id.artracker_mask) as? Boolean)?.let { return it }
        return when ((v.tag as? String)?.trim()) {
            TAG_MASK -> true
            TAG_UNMASK -> false
            else -> null
        }
    }

    fun isBlocked(v: View): Boolean {
        if (v is WebView || v is SurfaceView || v is TextureView || v is VideoView) return true
        var c: Class<*>? = v.javaClass
        while (c != null && c != View::class.java) {
            if (c.name.contains("MapView")) return true
            c = c.superclass
        }
        return false
    }
}
