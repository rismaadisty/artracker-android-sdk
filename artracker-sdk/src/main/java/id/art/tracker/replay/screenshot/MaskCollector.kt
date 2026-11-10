package id.art.tracker.replay.screenshot

import android.view.View
import android.view.ViewGroup
import id.art.tracker.privacy.MaskDecision
import id.art.tracker.privacy.MaskingPolicy

/** Finds the Compose regions below a Compose host view (implemented by [ComposeMaskCollector]). */
internal fun interface ComposeMaskApi {
    /** Adds the regions to mask to [out]; [parentMasked] is true when the host sits inside a masked view. */
    fun collect(view: View, parentMasked: Boolean, out: MutableList<MaskRect>)
}

/**
 * Collects the regions of a window's view tree that must be painted over in a screenshot: the same rules as the
 * wireframe walker ([MaskingPolicy]: passwords, sensitive inputs, `maskAllInputs`, `maskAllText`, explicit masks,
 * sensitive classes) plus blocked views (WebView, surfaces, maps), which are always masked.
 *
 * Main thread only, read-only. Fails closed: when anything cannot be read, [collect] returns false and the caller
 * must drop the frame; an unmasked frame is never sent. Views that draw nothing (invisible, no size, alpha 0) add no
 * rect; a masked container adds one rect and its children add none unless they overflow it.
 */
internal class MaskCollector(
    private val policy: MaskingPolicy,
    private val compose: ComposeMaskApi? = null,
    private val maxViews: Int = 20_000,
) {
    private val location = IntArray(2)
    private var visited = 0
    private val composeHostCache = HashMap<Class<*>, Boolean>()

    /** True when [out] is complete for [root]'s tree. */
    fun collect(root: View, out: MutableList<MaskRect>): Boolean = try {
        visited = 0
        visit(root, false, null, out)
        true
    } catch (_: Throwable) {
        false
    }

    private fun visit(view: View, parentMasked: Boolean, maskedBounds: MaskRect?, out: MutableList<MaskRect>) {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0 || view.alpha <= 0f) return
        check(++visited <= maxViews) { "view tree too large" }
        val rect = rectOf(view)
        when (policy.forView(view, parentMasked)) {
            MaskDecision.BLOCK -> {
                out.add(rect)
                return
            }
            MaskDecision.MASK -> {
                if (maskedBounds == null || !maskedBounds.contains(rect)) out.add(rect)
                descend(view, true, rect, out)
            }
            MaskDecision.NONE -> {
                if (isComposeHost(view)) {
                    // Without the Compose reader the tree cannot be inspected: mask the whole host.
                    val api = compose
                    if (api == null) {
                        out.add(rect)
                    } else {
                        api.collect(view, false, out)
                        // Views embedded with AndroidView are children of the host, not semantics nodes: they get
                        // the view rules too (a WebView, a map or a password field inside Compose).
                        descend(view, false, maskedBounds, out)
                    }
                } else {
                    descend(view, false, maskedBounds, out)
                }
            }
        }
    }

    private fun descend(view: View, masked: Boolean, bounds: MaskRect?, out: MutableList<MaskRect>) {
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i) ?: continue
            visit(child, masked, bounds, out)
        }
    }

    private fun rectOf(view: View): MaskRect {
        view.getLocationOnScreen(location)
        return MaskRect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    /** By class name, so Compose types are never referenced here. */
    private fun isComposeHost(view: View): Boolean = composeHostCache.getOrPut(view.javaClass) {
        var c: Class<*>? = view.javaClass
        var found = false
        while (c != null && c != View::class.java) {
            if (c.name == COMPOSE_HOST_CLASS) {
                found = true
                break
            }
            c = c.superclass
        }
        found
    }

    private companion object {
        const val COMPOSE_HOST_CLASS = "androidx.compose.ui.platform.AndroidComposeView"
    }
}
