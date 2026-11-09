package id.art.tracker.replay.capture

import android.view.View
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import id.art.tracker.privacy.ComposeMaskRules
import id.art.tracker.privacy.MaskDecision
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.model.VisualStyle
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import kotlin.math.roundToInt

/** Source of the unmerged semantics root of a Compose host view. */
internal fun interface SemanticsAccess {
    /** The unmerged root node, or null when [view] is not a readable Compose host. May throw. */
    fun rootNode(view: View): SemanticsNode?
}

/**
 * Reads `(view as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode`. `ViewRootForTest` is an internal
 * Compose API; any failure (missing class, changed API, detached owner) yields null so the walker degrades to a
 * placeholder block instead of crashing the app.
 */
internal object ReflectiveSemanticsAccess : SemanticsAccess {
    @OptIn(InternalComposeUiApi::class)
    override fun rootNode(view: View): SemanticsNode? =
        runCatching { (view as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode }.getOrNull()
}

/**
 * Converts a Compose host view (`AndroidComposeView`) into [Wireframe] nodes through the unmerged semantics tree.
 * Main thread only, read-only, no I/O.
 *
 * Compose is a `compileOnly` dependency: this class references Compose types, so it must be instantiated ONLY
 * behind `ClassAvailability.compose`; no always-loaded class may reference it directly (`ViewWalker` receives it as
 * a [ComposeWalkerApi]).
 *
 * Privacy contract (same as `ViewWalker`):
 * - masked nodes carry only `MaskingPolicy.maskText(text)`; raw text, content descriptions and tags never enter a
 *   [Wireframe] (content descriptions are never captured at all, they only influence the node type);
 * - `parentMasked` reaches children only from MASK decisions ([ComposeMaskRules]);
 * - if the tree cannot be read or anything throws, the result is a single masked `COMPOSE_BLOCK` node; [walk]
 *   never throws.
 *
 * Own budget: at most [maxNodes] nodes (then one `summarized` node, the `ViewWalker` cap does not cut Compose
 * subtrees) and depth 50. Not thread-safe: one walk at a time.
 */
internal class ComposeWalker(
    private val policy: MaskingPolicy,
    private val semanticsAccess: SemanticsAccess = ReflectiveSemanticsAccess,
    private val maxNodes: Int = 2000,
) : ComposeWalkerApi {

    override fun walk(view: View, parentMasked: Boolean): Wireframe? = try {
        val root = semanticsAccess.rootNode(view)
        if (root == null) {
            block(view)
        } else {
            Walk(view).node(root, 0, parentMasked)
        }
    } catch (_: Throwable) {
        block(view)
    }

    /** State of one walk: counters plus the host's density and screen origin. */
    private inner class Walk(host: View) {
        private val hostId = System.identityHashCode(host)
        private val density = density(host)
        private val originX: Int
        private val originY: Int
        private var count = 0
        private var capped = false

        init {
            val loc = IntArray(2)
            host.getLocationOnScreen(loc)
            originX = loc[0]
            originY = loc[1]
        }

        fun node(node: SemanticsNode, depth: Int, parentMasked: Boolean): Wireframe? {
            if (capped) return null
            val config = node.config
            if (isInvisible(config)) return null
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f) return null
            val x = dp(originX + bounds.left)
            val y = dp(originY + bounds.top)
            val w = dp(bounds.width)
            val h = dp(bounds.height)
            val id = 31 * hostId + node.id
            if (count >= maxNodes) {
                capped = true
                count++
                return Wireframe(id, x, y, w, h, WireframeType.CONTAINER, summarized = true)
            }
            count++
            if (depth > MAX_DEPTH) return Wireframe(id, x, y, w, h, WireframeType.CONTAINER, summarized = true)

            val masked = ComposeMaskRules.decide(policy.config, config, parentMasked) == MaskDecision.MASK
            val editable = config.getOrNull(SemanticsProperties.EditableText)
            val texts = config.getOrNull(SemanticsProperties.Text)
            val raw: String? = editable?.text ?: texts?.joinToString(" ") { it.text }
            val text = when {
                raw == null -> null
                masked -> MaskingPolicy.maskText(raw)
                else -> raw.take(MAX_TEXT_LENGTH)
            }
            val role = config.getOrNull(SemanticsProperties.Role)
            val type = when {
                editable != null || SemanticsProperties.Password in config -> WireframeType.INPUT
                role == Role.Button -> WireframeType.BUTTON
                role == Role.Image -> WireframeType.IMAGE
                raw != null -> WireframeType.TEXT
                SemanticsProperties.ContentDescription in config -> WireframeType.IMAGE
                else -> WireframeType.CONTAINER
            }
            val kids = node.children
            val children = ArrayList<Wireframe>(kids.size)
            for (child in kids) node(child, depth + 1, masked)?.let(children::add)
            val box = ComposeStyleReader.boxStyle(node, density)
            val textStyle = if (type == WireframeType.TEXT || type == WireframeType.INPUT) {
                ComposeStyleReader.textStyle(config, density)
            } else {
                null
            }
            val visual = VisualStyle(
                gradient = box.gradient, radiiDp = box.radiiDp,
                borderWidthDp = box.borderWidthDp, borderColor = box.borderColor,
                shadowDp = box.shadowDp, clip = box.clip,
                fontFamily = textStyle?.fontFamily, italic = textStyle?.italic ?: false,
                textAlign = textStyle?.align, wrap = textStyle?.wrap ?: false,
                lineHeightDp = textStyle?.lineHeightDp?.takeIf { textStyle.wrap },
            ).takeIf { it != EMPTY_VISUAL }
            return Wireframe(
                id, x, y, w, h, type, text = text,
                textColor = textStyle?.color, textSizeSp = textStyle?.sizeSp, fontWeight = textStyle?.weight,
                background = box.background, alpha = box.alpha, visual = visual,
                masked = masked, children = children,
            )
        }

        private fun dp(px: Float): Int = (px / density).roundToInt()
    }

    /** Masked, textless, childless placeholder of the host's size. Never throws. */
    private fun block(view: View): Wireframe {
        var x = 0
        var y = 0
        var w = 0
        var h = 0
        try {
            val density = density(view)
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            x = (loc[0] / density).roundToInt()
            y = (loc[1] / density).roundToInt()
            w = (view.width / density).roundToInt()
            h = (view.height / density).roundToInt()
        } catch (_: Throwable) {
            // keep zero bounds
        }
        return Wireframe(
            id = System.identityHashCode(view), x = x, y = y, width = w, height = h,
            type = WireframeType.COMPOSE_BLOCK, masked = true,
        )
    }

    private fun density(view: View): Float {
        val d = view.resources.displayMetrics.density
        return if (d > 0f) d else 1f
    }

    /**
     * `invisibleToUser()` is an experimental key that newer Compose versions deprecate or move; a failure to
     * resolve it must not fail the whole walk, so nodes are simply treated as visible then.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun isInvisible(config: SemanticsConfiguration): Boolean =
        try {
            SemanticsProperties.InvisibleToUser in config
        } catch (_: Throwable) {
            false
        }

    private companion object {
        val EMPTY_VISUAL = VisualStyle()
        const val MAX_DEPTH = 50
        const val MAX_TEXT_LENGTH = 200
    }
}
