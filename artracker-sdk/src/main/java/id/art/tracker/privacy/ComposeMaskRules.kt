package id.art.tracker.privacy

import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import id.art.tracker.PrivacyConfig

/**
 * Semantics key behind `Modifier.artrackerMask()` / `Modifier.artrackerUnmask()`: `true` masks the node and its
 * whole subtree, `false` opts the node out of the default masking rules (never a password).
 *
 * Merge policy (used when a merging ancestor folds this node's semantics into its own): `true` wins over `false`
 * (fail closed); otherwise the parent value is kept, else the child's. Two modifiers on the *same* layout node are
 * not merged through this policy: Compose keeps the first one, so `Modifier.artrackerMask().artrackerUnmask()`
 * masks and `Modifier.artrackerUnmask().artrackerMask()` does not.
 *
 * References Compose types: only touch it from code that runs when Compose is on the classpath.
 */
internal val ARTrackerMaskKey: SemanticsPropertyKey<Boolean> =
    SemanticsPropertyKey<Boolean>(name = "ARTrackerMask") { parent, child ->
        if (parent == true || child == true) true else parent ?: child
    }

/**
 * Masking decision for one Compose semantics node. Same priority as [MaskingPolicy] for views:
 * password, explicit unmask, explicit mask / masked ancestor, `maskAllInputs` for editable text,
 * `maskAllText` for text.
 *
 * Compose has no input-type semantic, so the numeric/phone/autofill rules of the view engine have no equivalent
 * here: a PIN or phone field is only masked through `maskAllInputs`, [Password][SemanticsProperties.Password]
 * semantics or `Modifier.artrackerMask()`.
 *
 * Fails closed: any exception yields [MaskDecision.MASK]. Loaded only together with the Compose walker.
 */
internal object ComposeMaskRules {
    fun decide(config: PrivacyConfig, node: SemanticsConfiguration, parentMasked: Boolean): MaskDecision = try {
        when {
            SemanticsProperties.Password in node -> MaskDecision.MASK
            node.getOrNull(ARTrackerMaskKey) == false -> MaskDecision.NONE
            node.getOrNull(ARTrackerMaskKey) == true || parentMasked -> MaskDecision.MASK
            config.maskAllInputs && SemanticsProperties.EditableText in node -> MaskDecision.MASK
            config.maskAllText && SemanticsProperties.Text in node -> MaskDecision.MASK
            else -> MaskDecision.NONE
        }
    } catch (_: Throwable) {
        MaskDecision.MASK
    }
}
