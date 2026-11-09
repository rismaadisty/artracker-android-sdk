package id.art.tracker.replay.rrweb

import id.art.tracker.privacy.UrlScrubber
import id.art.tracker.replay.diff.WireframeDiffer
import id.art.tracker.replay.diff.WireframeDiff
import id.art.tracker.replay.diff.displayText
import id.art.tracker.replay.model.VisualStyle
import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import java.util.Locale

internal enum class TouchKind { DOWN, MOVE, UP }

/**
 * Encodes wireframe frames as rrweb 2.x events (plain `Map`/`List`/`String`/`Number`/`Boolean`/`null`).
 * Stateful per replay session: it remembers which rrweb node id belongs to which [Wireframe.id].
 *
 * Reserved rrweb node ids: 1 document, 2 html, 3 head, 4 body, 5 doctype, 6 default `<style>` and 7 its text
 * (8..9 spare). Wireframe nodes get
 * ids from 10 in pre-order; a node's own text node takes the id right after the element's. Ids are never
 * reused within a snapshot generation: [fullSnapshot] and [reset] restart numbering at 10.
 *
 * Every full snapshot carries a fixed default stylesheet ([DEFAULT_CSS]) in `<head>` so a chunk is self-contained
 * in any rrweb player (sans-serif font, single-line text); inline styles still win over it.
 *
 * Privacy: the only text this encoder writes is [Wireframe.displayText] (the wireframe's own, already masked
 * text, or a fixed placeholder label) plus that constant stylesheet. Colors and sizes come from ints/floats only.
 */
internal class RrwebEncoder {
    private class NodeState(
        val nodeId: Int,
        val textId: Int?,
        val parentWf: Int,
        var relX: Int,
        var relY: Int,
        var attrs: Map<String, String>,
    ) {
        val children = ArrayList<Int>()
    }

    private val states = HashMap<Int, NodeState>()
    private var nextNodeId = FIRST_NODE_ID

    /** rrweb node id currently mapped to [wireframeId], or null. */
    fun nodeIdFor(wireframeId: Int): Int? = states[wireframeId]?.nodeId

    fun reset() {
        states.clear()
        nextNodeId = FIRST_NODE_ID
    }

    fun meta(href: String, widthDp: Int, heightDp: Int, ts: Long): Map<String, Any?> = mapOf(
        "type" to TYPE_META,
        "data" to mapOf("href" to UrlScrubber.scrub(href), "width" to widthDp, "height" to heightDp),
        "timestamp" to ts,
    )

    fun fullSnapshot(root: Wireframe, ts: Long): Map<String, Any?> {
        reset()
        val tree = serialize(root, WireframeDiffer.ROOT_PARENT_ID, 0, 0)
        val body = mapOf(
            "type" to NODE_ELEMENT,
            "tagName" to "body",
            "attributes" to mapOf(
                "style" to "margin:0;position:relative;width:${dim(root.width)}px;height:${dim(root.height)}px;overflow:hidden",
            ),
            "childNodes" to listOf(tree),
            "id" to BODY_ID,
        )
        val styleText = mapOf("type" to NODE_TEXT, "textContent" to DEFAULT_CSS, "id" to STYLE_TEXT_ID)
        val style = mapOf(
            "type" to NODE_ELEMENT, "tagName" to "style", "attributes" to emptyMap<String, Any?>(),
            "childNodes" to listOf(styleText), "id" to STYLE_ID,
        )
        val head = mapOf(
            "type" to NODE_ELEMENT, "tagName" to "head", "attributes" to emptyMap<String, Any?>(),
            "childNodes" to listOf(style), "id" to HEAD_ID,
        )
        val html = mapOf(
            "type" to NODE_ELEMENT, "tagName" to "html", "attributes" to emptyMap<String, Any?>(),
            "childNodes" to listOf(head, body), "id" to HTML_ID,
        )
        val doctype = mapOf("type" to NODE_DOCUMENT_TYPE, "name" to "html", "publicId" to "", "systemId" to "", "id" to DOCTYPE_ID)
        val document = mapOf("type" to NODE_DOCUMENT, "childNodes" to listOf(doctype, html), "id" to DOCUMENT_ID)
        return mapOf(
            "type" to TYPE_FULL_SNAPSHOT,
            "data" to mapOf("node" to document, "initialOffset" to mapOf("left" to 0, "top" to 0)),
            "timestamp" to ts,
        )
    }

    /** Null when [diff] is empty or none of it can be mapped to nodes of the current snapshot. */
    fun mutation(diff: WireframeDiff, ts: Long): Map<String, Any?>? {
        if (diff.isEmpty) return null
        val removes = ArrayList<Map<String, Any?>>()
        for ((parentWf, id) in diff.removes) {
            val st = states[id] ?: continue
            val parentNode = parentNodeId(parentWf) ?: continue
            removes.add(mapOf("parentId" to parentNode, "id" to st.nodeId))
            drop(id)
        }
        // Geometry state must be final (ancestors first: updates are pre-order) before new nodes are
        // positioned relative to their parents, so this runs before the adds are serialized.
        val attributes = ArrayList<Map<String, Any?>>()
        for (w in diff.updates) {
            val st = states[w.id] ?: continue
            val (px, py) = absOf(st.parentWf) ?: continue
            st.relX = w.x - px
            st.relY = w.y - py
            val fresh = attributesOf(w, st.relX, st.relY)
            val changed = LinkedHashMap<String, Any?>()
            for ((k, v) in fresh) if (st.attrs[k] != v) changed[k] = v
            for (k in st.attrs.keys) if (k !in fresh) changed[k] = null
            st.attrs = fresh
            if (changed.isNotEmpty()) attributes.add(mapOf("id" to st.nodeId, "attributes" to changed))
        }
        val adds = ArrayList<Map<String, Any?>>()
        diff.adds.forEachIndexed { i, (parentWf, node) ->
            val parentNode = parentNodeId(parentWf) ?: return@forEachIndexed
            val (px, py) = absOf(parentWf) ?: return@forEachIndexed
            val nextId = diff.addBefore.getOrNull(i)?.let { states[it]?.nodeId }
            adds.add(mapOf("parentId" to parentNode, "nextId" to nextId, "node" to serialize(node, parentWf, px, py)))
        }
        val texts = ArrayList<Map<String, Any?>>()
        for ((id, value) in diff.texts) {
            val textId = states[id]?.textId ?: continue
            texts.add(mapOf("id" to textId, "value" to value))
        }
        if (removes.isEmpty() && adds.isEmpty() && texts.isEmpty() && attributes.isEmpty()) return null
        return mapOf(
            "type" to TYPE_INCREMENTAL,
            "data" to mapOf(
                "source" to SOURCE_MUTATION,
                "texts" to texts,
                "attributes" to attributes,
                "removes" to removes,
                "adds" to adds,
            ),
            "timestamp" to ts,
        )
    }

    fun touch(kind: TouchKind, xDp: Int, yDp: Int, ts: Long): Map<String, Any?> {
        val data: Map<String, Any?> = when (kind) {
            TouchKind.DOWN -> mapOf("source" to SOURCE_MOUSE_INTERACTION, "type" to TOUCH_START, "id" to BODY_ID, "x" to xDp, "y" to yDp)
            TouchKind.UP -> mapOf("source" to SOURCE_MOUSE_INTERACTION, "type" to TOUCH_END, "id" to BODY_ID, "x" to xDp, "y" to yDp)
            TouchKind.MOVE -> mapOf(
                "source" to SOURCE_TOUCH_MOVE,
                "positions" to listOf(mapOf("x" to xDp, "y" to yDp, "id" to BODY_ID, "timeOffset" to 0)),
            )
        }
        return mapOf("type" to TYPE_INCREMENTAL, "data" to data, "timestamp" to ts)
    }

    private fun parentNodeId(parentWf: Int): Int? = if (parentWf == WireframeDiffer.ROOT_PARENT_ID) BODY_ID else states[parentWf]?.nodeId

    /** Absolute screen position (dp) of a mapped node, summed from parent-relative offsets; null if unmapped. */
    private fun absOf(wireframeId: Int): Pair<Int, Int>? {
        var x = 0
        var y = 0
        var cur = wireframeId
        var guard = 0
        while (cur != WireframeDiffer.ROOT_PARENT_ID) {
            val st = states[cur] ?: return null
            x += st.relX
            y += st.relY
            cur = st.parentWf
            if (++guard > MAX_CHAIN) return null
        }
        return x to y
    }

    private fun drop(id: Int) {
        val st = states.remove(id) ?: return
        states[st.parentWf]?.children?.remove(id)
        for (child in st.children.toList()) drop(child)
    }

    private fun serialize(w: Wireframe, parentWf: Int, parentAbsX: Int, parentAbsY: Int): Map<String, Any?> {
        val nodeId = nextNodeId++
        val label = w.displayText()
        val textId = if (label != null) nextNodeId++ else null
        val relX = w.x - parentAbsX
        val relY = w.y - parentAbsY
        val attrs = attributesOf(w, relX, relY)
        val st = NodeState(nodeId, textId, parentWf, relX, relY, attrs)
        states[w.id] = st
        states[parentWf]?.children?.add(w.id)

        val childNodes = ArrayList<Map<String, Any?>>(w.children.size + 1)
        if (label != null && textId != null) {
            childNodes.add(mapOf("type" to NODE_TEXT, "textContent" to label, "id" to textId))
        }
        for (c in w.children) childNodes.add(serialize(c, w.id, w.x, w.y))
        return mapOf(
            "type" to NODE_ELEMENT,
            "tagName" to if (w.type == WireframeType.TEXT) "span" else "div",
            "attributes" to attrs,
            "childNodes" to childNodes,
            "id" to nodeId,
        )
    }

    private fun attributesOf(w: Wireframe, relX: Int, relY: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["style"] = styleOf(w, relX, relY)
        out["data-artracker-type"] = w.type.name
        if (w.masked) out["data-artracker-masked"] = "true"
        return out
    }

    private fun styleOf(w: Wireframe, relX: Int, relY: Int): String {
        val sb = StringBuilder(96)
        sb.append("position:absolute;left:").append(relX).append("px;top:").append(relY).append("px;width:")
            .append(dim(w.width)).append("px;height:").append(dim(w.height)).append("px;")
        val visual = w.visual
        val image = visual?.image?.takeIf { IMAGE_URI.matches(it) }
        when (w.type) {
            WireframeType.IMAGE -> if (image == null) sb.append("background-color:").append(IMAGE_GREY).append(';')
            WireframeType.WEB, WireframeType.SURFACE, WireframeType.COMPOSE_BLOCK -> sb.append(BLOCKED_BACKGROUND)
            else -> w.background?.let { sb.append("background-color:").append(css(it)).append(';') }
        }
        val hasText = w.displayText() != null
        if (visual != null) appendShape(sb, visual, image)
        if (hasText) {
            w.textColor?.let { sb.append("color:").append(css(it)).append(';') }
            w.textSizeSp?.let { size -> number(size)?.let { sb.append("font-size:").append(it).append("px;") } }
            w.fontWeight?.let { sb.append("font-weight:").append(it.coerceIn(MIN_FONT_WEIGHT, MAX_FONT_WEIGHT)).append(';') }
            if (visual != null) appendText(sb, visual)
        }
        if (w.alpha < 1f) number(w.alpha.coerceIn(0f, 1f))?.let { sb.append("opacity:").append(it).append(';') }
        // Text boxes are often measured shorter than their glyphs (parent clipping, font padding): clipping them
        // again would cut letters in half. Boxes clip, so that scrolled-out items stay hidden.
        sb.append(if (w.type == WireframeType.TEXT) "overflow:visible;" else "overflow:hidden;")
        return sb.toString()
    }

    /**
     * Radius, border, shadow, gradient and icon. Every value is rebuilt from numbers or checked against a strict
     * pattern before it reaches CSS: a malformed or hostile string is dropped, never written.
     */
    private fun appendShape(sb: StringBuilder, v: VisualStyle, image: String?) {
        radiusCss(v.radiiDp)?.let { sb.append("border-radius:").append(it).append(';') }
        val bw = v.borderWidthDp?.let(::number)
        if (bw != null && bw != "0" && v.borderColor != null) {
            sb.append("border:").append(bw).append("px solid ").append(css(v.borderColor)).append(';')
        }
        v.shadowDp?.takeIf { it > 0f }?.let { e ->
            val blur = number(e * 2f)
            number(e)?.let { sb.append("box-shadow:0px ").append(it).append("px ").append(blur).append("px rgba(0,0,0,0.14);") }
        }
        if (image != null) {
            val fit = (v.imageFit ?: id.art.tracker.replay.model.ImageFit.CONTAIN).css
            sb.append("background-image:url(\"").append(image).append("\");background-size:").append(fit)
                .append(";background-repeat:no-repeat;background-position:center;")
        } else {
            v.gradient?.takeIf { GRADIENT.matches(it) && !it.contains("url", ignoreCase = true) }
                ?.let { sb.append("background-image:").append(it).append(';') }
        }
    }

    private fun appendText(sb: StringBuilder, v: VisualStyle) {
        v.fontFamily?.takeIf { it in FONT_FAMILIES }?.let { sb.append("font-family:").append(it).append(';') }
        if (v.italic) sb.append("font-style:italic;")
        if (v.wrap) {
            sb.append("display:block;white-space:normal;")
            v.textAlign?.let { sb.append("text-align:").append(it.css).append(';') }
        } else {
            v.textAlign?.let { sb.append("justify-content:").append(it.flex).append(';') }
        }
        v.lineHeightDp?.takeIf { it > 0f }?.let(::number)?.let { sb.append("line-height:").append(it).append("px;") }
    }

    /** One value when all four corners match, else the four in CSS order; null for anything not 4 finite, >= 0. */
    private fun radiusCss(radii: List<Float>?): String? {
        if (radii == null || radii.size != 4) return null
        val parts = radii.map(::number)
        if (parts.any { it == null }) return null
        if (parts.all { it == parts[0] }) return if (parts[0] == "0") null else "${parts[0]}px"
        return parts.joinToString(" ") { "${it}px" }
    }

    private fun dim(v: Int): Int = if (v < 0) 0 else v

    /** `#rrggbb` for opaque ARGB ints, `rgba(r,g,b,a)` otherwise. Locale-independent. */
    private fun css(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (a == 0xFF) return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b)
        return "rgba($r,$g,$b,${number(a / 255f)})"
    }

    /** Up to 3 decimals, trailing zeros trimmed; null for NaN/infinite or negative. */
    private fun number(v: Float): String? {
        if (v.isNaN() || v.isInfinite() || v < 0f) return null
        val s = String.format(Locale.ROOT, "%.3f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    private companion object {
        const val FIRST_NODE_ID = 10
        const val DOCUMENT_ID = 1
        const val HTML_ID = 2
        const val HEAD_ID = 3
        const val BODY_ID = 4
        const val DOCTYPE_ID = 5
        const val STYLE_ID = 6
        const val STYLE_TEXT_ID = 7
        const val MAX_CHAIN = 1_000
        const val MIN_FONT_WEIGHT = 1
        const val MAX_FONT_WEIGHT = 1000
        val FONT_FAMILIES = setOf("sans-serif", "serif", "monospace", "cursive")
        val IMAGE_URI = Regex("^data:image/(png|webp|jpeg);base64,[A-Za-z0-9+/=]{1,200000}$")
        val GRADIENT = Regex("^linear-gradient\\([0-9a-zA-Z#.,%() \\-]{1,400}\\)$")

        const val TYPE_FULL_SNAPSHOT = 2
        const val TYPE_INCREMENTAL = 3
        const val TYPE_META = 4
        const val NODE_DOCUMENT = 0
        const val NODE_DOCUMENT_TYPE = 1
        const val NODE_ELEMENT = 2
        const val NODE_TEXT = 3
        const val SOURCE_MUTATION = 0
        const val SOURCE_MOUSE_INTERACTION = 2
        const val SOURCE_TOUCH_MOVE = 6
        const val TOUCH_START = 7
        const val TOUCH_END = 9

        /**
         * Constant, content-free base theme: typography and box model only. Fills, borders and radii are never
         * invented here: the walkers write the app's real ones inline, and a node without them is transparent.
         */
        const val DEFAULT_CSS =
            "html,body{margin:0;padding:0;font-family:Roboto,-apple-system,BlinkMacSystemFont,sans-serif;" +
                "font-size:12px;line-height:1.25;color:#0f172a;background:#fafafa}" +
                "[data-artracker-type]{box-sizing:border-box}" +
                "[data-artracker-type=\"TEXT\"]{display:flex;align-items:center;font-size:11px;line-height:1.15;" +
                "color:#1e293b;white-space:nowrap}"

        const val IMAGE_GREY = "#cccccc"
        const val BLOCKED_BACKGROUND =
            "background:repeating-linear-gradient(45deg,#bdbdbd,#bdbdbd 4px,#e0e0e0 4px,#e0e0e0 8px);"
    }
}
