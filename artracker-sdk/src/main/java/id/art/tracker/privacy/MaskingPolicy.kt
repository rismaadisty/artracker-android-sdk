package id.art.tracker.privacy

import android.view.View
import android.widget.TextView
import id.art.tracker.PrivacyConfig
import kotlin.math.min

/** What the view walker must do with a node. */
internal enum class MaskDecision { NONE, MASK, BLOCK }

/**
 * Decides how a [View] is masked. Priority: password, then block, then explicit unmask,
 * then explicit mask / masked parent / sensitive class, then the default input/text rules.
 * Fails closed: any exception while evaluating the rules yields [MaskDecision.MASK].
 */
internal class MaskingPolicy(internal val config: PrivacyConfig) {

    fun forView(view: View, parentMasked: Boolean): MaskDecision = try {
        decide(view, parentMasked)
    } catch (_: Throwable) {
        MaskDecision.MASK
    }

    private fun decide(view: View, parentMasked: Boolean): MaskDecision {
        if (ViewMaskRules.isPassword(view)) return MaskDecision.MASK
        if (ViewMaskRules.isBlocked(view)) return MaskDecision.BLOCK
        val explicit = ViewMaskRules.explicitMask(view)
        if (explicit == false) return MaskDecision.NONE
        if (explicit == true || parentMasked || isSensitiveClass(view)) return MaskDecision.MASK
        // Sensitive input class/autofill hints apply to any view (fail closed); maskAllInputs to any input.
        if (ViewMaskRules.isSensitiveInput(view)) return MaskDecision.MASK
        if (config.maskAllInputs && ViewMaskRules.isInput(view)) return MaskDecision.MASK
        if (config.maskAllText && view is TextView) return MaskDecision.MASK
        return MaskDecision.NONE
    }

    private fun isSensitiveClass(view: View): Boolean =
        config.sensitiveViewClasses.any { it.isInstance(view) }

    companion object {
        private const val MAX_MASK_LENGTH = 32

        /** `*` repeated for the text length, capped at 32 so the length leaks as little as possible. */
        fun maskText(s: CharSequence): String = "*".repeat(min(s.length, MAX_MASK_LENGTH))
    }
}
