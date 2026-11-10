package id.art.tracker.replay.screenshot

import android.view.View
import androidx.compose.ui.semantics.SemanticsNode
import id.art.tracker.privacy.ComposeMaskRules
import id.art.tracker.privacy.MaskDecision
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.capture.ReflectiveSemanticsAccess
import id.art.tracker.replay.capture.SemanticsAccess
import kotlin.math.roundToInt

/**
 * Mask-only pass over a Compose host's unmerged semantics tree: the regions of password, sensitive and explicitly
 * masked nodes, and every text node when `maskAllText` is set ([ComposeMaskRules], the rules of `ComposeWalker`),
 * without reading any style. Main thread only.
 *
 * Like `ComposeWalker`, this class links Compose types and must be created only behind `ClassAvailability.compose`
 * (see [ComposeMaskCollectorFactory]). Fails closed: a tree that cannot be read masks the whole host.
 */
internal class ComposeMaskCollector(
    private val policy: MaskingPolicy,
    private val semanticsAccess: SemanticsAccess = ReflectiveSemanticsAccess,
    private val maxNodes: Int = 5_000,
) : ComposeMaskApi {

    override fun collect(view: View, parentMasked: Boolean, out: MutableList<MaskRect>) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val hostRect = MaskRect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        val added = out.size
        try {
            val root = semanticsAccess.rootNode(view)
            if (root == null) {
                out.add(hostRect)
                return
            }
            var count = 0
            fun visit(node: SemanticsNode, masked: Boolean, bounds: MaskRect?) {
                check(++count <= maxNodes) { "semantics tree too large" }
                val isMasked = ComposeMaskRules.decide(policy.config, node.config, masked) == MaskDecision.MASK
                var childBounds = bounds
                if (isMasked) {
                    val b = node.boundsInRoot
                    if (b.width > 0f && b.height > 0f) {
                        val rect = MaskRect(
                            loc[0] + b.left.roundToInt(), loc[1] + b.top.roundToInt(),
                            loc[0] + b.right.roundToInt(), loc[1] + b.bottom.roundToInt(),
                        )
                        if (bounds == null || !bounds.contains(rect)) out.add(rect)
                        childBounds = rect
                    }
                }
                for (child in node.children) visit(child, isMasked, childBounds)
            }
            visit(root, parentMasked, if (parentMasked) hostRect else null)
        } catch (_: Throwable) {
            // Whatever was collected before the failure is covered by the host rectangle.
            while (out.size > added) out.removeAt(out.size - 1)
            out.add(hostRect)
        }
    }
}

/** The only place that instantiates [ComposeMaskCollector]; call it only after `ClassAvailability.compose` said yes. */
internal object ComposeMaskCollectorFactory {
    fun create(policy: MaskingPolicy): ComposeMaskApi = ComposeMaskCollector(policy)
}
