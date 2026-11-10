package id.art.tracker.replay.screenshot

import android.view.View
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode

/** Source of the unmerged semantics root of a Compose host view. */
internal fun interface SemanticsAccess {
    /** The unmerged root node, or null when [view] is not a readable Compose host. May throw. */
    fun rootNode(view: View): SemanticsNode?
}

/**
 * Reads `(view as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode`. `ViewRootForTest` is an internal
 * Compose API; any failure (missing class, changed API, detached owner) yields null so the mask collector covers
 * the whole host instead of crashing the app.
 */
internal object ReflectiveSemanticsAccess : SemanticsAccess {
    @OptIn(InternalComposeUiApi::class)
    override fun rootNode(view: View): SemanticsNode? =
        runCatching { (view as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode }.getOrNull()
}
