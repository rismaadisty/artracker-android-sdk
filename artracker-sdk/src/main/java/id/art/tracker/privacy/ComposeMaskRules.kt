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
 * The autofill rules of the view engine have a Compose equivalent from UI 1.8 on: a node whose `ContentType` is a
 * password is always masked (not even an unmask opts out), and one whose `ContentType` is sensitive (card, phone,
 * e-mail, address, one-time code, or one whose hints cannot be read) is masked unless explicitly unmasked. Compose
 * has no input-type semantic (the keyboard type is not in the semantics tree), so a PIN or number field without a
 * `ContentType` is only masked through `maskAllInputs`, [Password][SemanticsProperties.Password] semantics or
 * `Modifier.artrackerMask()`. On Compose 1.7 at runtime the `ContentType` rules are skipped and the rest applies as before.
 *
 * Fails closed: any exception yields [MaskDecision.MASK]. Loaded only together with the Compose mask collector.
 */
internal object ComposeMaskRules {
    fun decide(config: PrivacyConfig, node: SemanticsConfiguration, parentMasked: Boolean): MaskDecision = try {
        // Only when the runtime Compose has the public property: on 1.7 the call would throw, and the catch below
        // would then mask every node of the page.
        val contentType =
            if (ComposeContentTypeRules.available) ComposeContentTypeRules.classify(node) else ContentTypeClass.NONE
        when {
            SemanticsProperties.Password in node -> MaskDecision.MASK
            contentType == ContentTypeClass.PASSWORD -> MaskDecision.MASK
            node.getOrNull(ARTrackerMaskKey) == false -> MaskDecision.NONE
            node.getOrNull(ARTrackerMaskKey) == true || parentMasked -> MaskDecision.MASK
            contentType == ContentTypeClass.SENSITIVE -> MaskDecision.MASK
            config.maskAllInputs && SemanticsProperties.EditableText in node -> MaskDecision.MASK
            config.maskAllText && SemanticsProperties.Text in node -> MaskDecision.MASK
            else -> MaskDecision.NONE
        }
    } catch (_: Throwable) {
        MaskDecision.MASK
    }
}
