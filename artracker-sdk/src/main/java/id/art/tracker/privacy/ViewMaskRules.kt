package id.art.tracker.privacy

import android.os.Build
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import android.widget.VideoView
import id.art.tracker.sdk.R
import java.util.concurrent.ConcurrentHashMap

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

    // View.AUTOFILL_HINT_* values (API 26, e.g. AUTOFILL_HINT_NEW_PASSWORD; SMS OTP is API 28). Literals keep minSdk 24 safe.
    // "phoneNumber", "phoneNumberDevice" and "phoneNational" are the androidx.autofill HintConstants that Compose's
    // ContentType.PhoneNumber* and modern Views use; View's own constant is only "phone".
    private val SENSITIVE_AUTOFILL_HINTS = setOf(
        "password", "newPassword", "creditCardNumber", "creditCardSecurityCode",
        "phone", "phoneNumber", "phoneNumberDevice", "phoneNational", "emailAddress", "postalAddress", "smsOTPCode",
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
        val tag = (v.tag as? String)?.trim() ?: return null
        return when {
            tag.equals(TAG_MASK, ignoreCase = true) -> true
            tag.equals(TAG_UNMASK, ignoreCase = true) -> false
            else -> null
        }
    }

    /**
     * True for anything that accepts text input, not just [android.widget.EditText]: a text editor
     * per the framework, or a [TextView] with an input type. Fails closed (true) if the view throws.
     */
    fun isInput(v: View): Boolean = try {
        // A Compose host reports onCheckIsTextEditor() = true while any of its text fields has focus, which would mask
        // the whole page. Its fields are judged one by one through the semantics tree (see ComposeMaskRules); a host
        // whose tree cannot be read is masked as a whole by the mask collector, so skipping it here fails nowhere open.
        !isComposeHost(v) && (v is EditText || v.onCheckIsTextEditor() || (v is TextView && v.inputType != 0))
    } catch (_: Throwable) {
        true
    }

    private const val COMPOSE_HOST_CLASS = "androidx.compose.ui.platform.AndroidComposeView"
    private val composeHostCache = ConcurrentHashMap<Class<*>, Boolean>()

    /** By class name, so Compose types are never referenced here. */
    fun isComposeHost(v: View): Boolean = composeHostCache.getOrPut(v.javaClass) {
        var c: Class<*>? = v.javaClass
        while (c != null && c != View::class.java) {
            if (c.name == COMPOSE_HOST_CLASS) return@getOrPut true
            c = c.superclass
        }
        false
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
