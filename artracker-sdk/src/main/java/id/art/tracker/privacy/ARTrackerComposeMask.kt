@file:JvmName("ARTrackerComposeMask")

package id.art.tracker.privacy

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics

/*
 * Compose is a compileOnly dependency of the SDK: this file mentions Compose types by necessity, so it is only
 * loaded when a host app that uses Compose calls these extensions. No always-loaded SDK class may reference it.
 */

/**
 * Masks this composable and its whole subtree in session replay (text is replaced by `*`).
 * Descendant semantics stay separate nodes (`mergeDescendants = false`), so each child is masked individually.
 */
public fun Modifier.artrackerMask(): Modifier =
    semantics(mergeDescendants = false) { this[ARTrackerMaskKey] = true }

/**
 * Opts this composable out of masking in session replay, overriding `maskAllInputs` and `maskAllText`.
 * Password fields are always masked regardless. Applies to this node only, not to its descendants.
 */
public fun Modifier.artrackerUnmask(): Modifier =
    semantics(mergeDescendants = false) { this[ARTrackerMaskKey] = false }
