@file:JvmName("ARTrackerMask")

package id.art.tracker.privacy

import android.view.View
import id.art.tracker.sdk.R

/** Masks this view and its whole subtree in session replay. */
public fun View.artrackerMask() {
    setTag(R.id.artracker_mask, true)
}

/**
 * Opts this view out of masking in session replay, overriding `maskAllInputs` and `maskAllText`.
 * Password fields are always masked regardless.
 */
public fun View.artrackerUnmask() {
    setTag(R.id.artracker_mask, false)
}
