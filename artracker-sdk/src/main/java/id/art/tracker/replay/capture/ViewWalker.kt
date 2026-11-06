package id.art.tracker.replay.capture

import android.graphics.drawable.ColorDrawable
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import id.art.tracker.privacy.MaskDecision
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.privacy.ViewMaskRules
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import kotlin.math.min
import kotlin.math.roundToInt

/** Reads a Jetpack Compose host view into wireframe nodes (implemented in a later task). */
internal fun interface ComposeWalkerApi {
    fun walk(view: View, parentMasked: Boolean): Wireframe?
}

/**
 * Converts a live view hierarchy to [Wireframe] nodes. Main thread only, read-only, no I/O.
 *
 * Privacy contract:
 * - raw text, hints, content descriptions and tags of masked nodes never enter a [Wireframe];
 * - `parentMasked = true` is passed to children only when the parent's decision was MASK;
 * - blocked views (WebView, MapView, surfaces) are emitted as childless, textless nodes and never descended;
 * - any exception while reading a view yields a masked placeholder (fail closed); [walk] never throws.
 *
 * Not thread-safe: one walk at a time (it keeps per-walk counters and a scratch array).
 */
internal class ViewWalker(
    private val policy: MaskingPolicy,
    private val composeWalker: ComposeWalkerApi? = null,
    private val maxNodes: Int = 2000,
    private val maxDepth: Int = 50,
) {
    private var count = 0
    private var capped = false
    private val location = IntArray(2)
    private val composeHostCache = HashMap<Class<*>, Boolean>()

    fun walk(root: View): Wireframe? {
        count = 0
        capped = false
        return try {
            node(root, 0, false)
        } catch (_: Throwable) {
            null
        }
    }

    private fun node(view: View, depth: Int, parentMasked: Boolean): Wireframe? {
        if (capped) return null
        return try {
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0 || view.alpha <= 0f) {
                return null
            }
            if (count >= maxNodes) {
                capped = true
                count++
                return summary(view)
            }
            count++
            if (depth > maxDepth) return summary(view)
            build(view, depth, parentMasked)
        } catch (_: Throwable) {
            failClosed(view)
        }
    }

    private fun build(view: View, depth: Int, parentMasked: Boolean): Wireframe {
        val density = density(view)
        val x = screenX(view, density)
        val y = screenY(view, density)
        val w = dp(view.width, density)
        val h = dp(view.height, density)
        val id = System.identityHashCode(view)
        val alpha = view.alpha
        val decision = policy.forView(view, parentMasked)

        if (decision == MaskDecision.BLOCK || ViewMaskRules.isBlocked(view)) {
            val type = if (view is WebView) WireframeType.WEB else WireframeType.SURFACE
            return Wireframe(id, x, y, w, h, type, alpha = alpha, masked = true)
        }
        val masked = decision == MaskDecision.MASK

        if (composeWalker != null && isComposeHost(view)) {
            val composed = composeWalker.walk(view, masked) ?: return failClosed(view)
            count += (nodeCount(composed) - 1).coerceAtLeast(0)
            return composed
        }

        var text: String? = null
        var textColor: Int? = null
        var textSizeSp: Float? = null
        if (view is TextView) {
            val raw: CharSequence? = view.text
            text = when {
                raw == null -> null
                masked -> MaskingPolicy.maskText(raw)
                else -> raw.subSequence(0, min(raw.length, MAX_TEXT_LENGTH)).toString()
            }
            textColor = view.currentTextColor
            textSizeSp = view.textSize / (density * view.resources.configuration.fontScale)
        }
        val background = (view.background as? ColorDrawable)?.color

        val children = if (view is ViewGroup) {
            val childCount = view.childCount
            val list = ArrayList<Wireframe>(childCount)
            for (i in 0 until childCount) {
                val child = view.getChildAt(i) ?: continue
                node(child, depth + 1, masked)?.let(list::add)
            }
            list
        } else {
            emptyList()
        }

        return Wireframe(
            id = id, x = x, y = y, width = w, height = h,
            type = typeOf(view),
            text = text, textColor = textColor, textSizeSp = textSizeSp,
            background = background, alpha = alpha, masked = masked, children = children,
        )
    }

    private fun typeOf(view: View): WireframeType = when {
        ViewMaskRules.isInput(view) -> WireframeType.INPUT
        view is Button -> WireframeType.BUTTON
        view is TextView -> WireframeType.TEXT
        view is ImageView -> WireframeType.IMAGE
        view is SurfaceView || view is TextureView || view is VideoView -> WireframeType.SURFACE
        else -> WireframeType.CONTAINER
    }

    /** One node standing in for a subtree that exceeded the node or depth budget. */
    private fun summary(view: View): Wireframe {
        val density = density(view)
        return Wireframe(
            id = System.identityHashCode(view),
            x = screenX(view, density), y = screenY(view, density),
            width = dp(view.width, density), height = dp(view.height, density),
            type = WireframeType.CONTAINER, summarized = true,
        )
    }

    /** Masked, textless, childless placeholder used when reading [view] threw. */
    private fun failClosed(view: View): Wireframe {
        var x = 0
        var y = 0
        var w = 0
        var h = 0
        try {
            val density = density(view)
            x = screenX(view, density)
            y = screenY(view, density)
            w = dp(view.width, density)
            h = dp(view.height, density)
        } catch (_: Throwable) {
            // keep zero bounds
        }
        return Wireframe(
            id = System.identityHashCode(view), x = x, y = y, width = w, height = h,
            type = WireframeType.CONTAINER, masked = true,
        )
    }

    private fun density(view: View): Float {
        val d = view.resources.displayMetrics.density
        return if (d > 0f) d else 1f
    }

    private fun screenX(view: View, density: Float): Int {
        view.getLocationOnScreen(location)
        return dp(location[0], density)
    }

    private fun screenY(view: View, density: Float): Int {
        view.getLocationOnScreen(location)
        return dp(location[1], density)
    }

    private fun dp(px: Int, density: Float): Int = (px / density).roundToInt()

    /** Matches by class name so Compose types are never referenced (Compose is optional). */
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

    private fun nodeCount(root: Wireframe): Int {
        var n = 0
        val stack = ArrayDeque<Wireframe>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val w = stack.removeLast()
            n++
            w.children.forEach(stack::addLast)
        }
        return n
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 200
        const val COMPOSE_HOST_CLASS = "androidx.compose.ui.platform.AndroidComposeView"
    }
}
