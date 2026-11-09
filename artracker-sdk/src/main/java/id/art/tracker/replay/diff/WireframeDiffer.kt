package id.art.tracker.replay.diff

import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType

/**
 * Structural difference between two consecutive frames, keyed by [Wireframe.id].
 *
 * - [adds]: `(parentId, node)`; the node carries its whole next-frame subtree, so a new subtree is ONE add.
 *   The root's parent is [WireframeDiffer.ROOT_PARENT_ID].
 * - [removes]: `(parentId, id)` of the topmost removed node only; the player drops its subtree with it.
 * - [updates]: surviving nodes whose parent-relative bounds, style, alpha or mask flag changed.
 * - [texts]: `(id, newText)` for surviving nodes whose displayed text changed.
 * - [addBefore]: parallel to [adds]; the id of the nearest following sibling that already exists in the
 *   previous frame at the same position (the insert-before anchor), or null to append. Not part of [isEmpty].
 */
internal data class WireframeDiff(
    val adds: List<Pair<Int, Wireframe>>,
    val removes: List<Pair<Int, Int>>,
    val updates: List<Wireframe>,
    val texts: List<Pair<Int, String>>,
    val addBefore: List<Int?> = emptyList(),
) {
    val isEmpty: Boolean get() = adds.isEmpty() && removes.isEmpty() && updates.isEmpty() && texts.isEmpty()
}

/** Fixed placeholder labels; the only strings the replay output may contain besides [Wireframe.text]. */
internal const val LABEL_SUMMARIZED = "[…]"
internal const val LABEL_BLOCKED = "[blocked]"

/**
 * The text a node shows: the wireframe's own (already masked) text, or a fixed label for summarized
 * subtrees and masked WEB/SURFACE/COMPOSE_BLOCK placeholders. Never anything else.
 */
internal fun Wireframe.displayText(): String? = when {
    summarized -> LABEL_SUMMARIZED
    text != null -> text
    masked && (type == WireframeType.WEB || type == WireframeType.SURFACE || type == WireframeType.COMPOSE_BLOCK) ->
        LABEL_BLOCKED
    else -> null
}

internal object WireframeDiffer {
    /** Virtual parent id of the root node. */
    const val ROOT_PARENT_ID: Int = -1

    fun diff(prev: Wireframe, next: Wireframe): WireframeDiff {
        val p = Index(prev)
        val n = Index(next)
        val replaced = replacedIds(p, n, next)

        val removes = ArrayList<Pair<Int, Int>>()
        collectRemoves(prev, ROOT_PARENT_ID, p, n, replaced, removes)

        val adds = ArrayList<Pair<Int, Wireframe>>()
        val addBefore = ArrayList<Int?>()
        val updates = ArrayList<Wireframe>()
        val texts = ArrayList<Pair<Int, String>>()
        collectNext(next, ROOT_PARENT_ID, null, p, n, replaced, adds, addBefore, updates, texts)
        return WireframeDiff(adds, removes, updates, texts, addBefore)
    }

    /** id -> node, id -> parent id and position among its siblings. First occurrence wins on duplicate ids. */
    private class Index(root: Wireframe) {
        val nodes = HashMap<Int, Wireframe>()
        val parent = HashMap<Int, Int>()
        val position = HashMap<Int, Int>()

        init {
            visit(root, ROOT_PARENT_ID, 0)
        }

        private fun visit(w: Wireframe, parentId: Int, pos: Int) {
            if (nodes.containsKey(w.id)) return
            nodes[w.id] = w
            parent[w.id] = parentId
            position[w.id] = pos
            w.children.forEachIndexed { i, c -> visit(c, w.id, i) }
        }

        fun relX(w: Wireframe): Int = w.x - (nodes[parent[w.id]]?.x ?: 0)
        fun relY(w: Wireframe): Int = w.y - (nodes[parent[w.id]]?.y ?: 0)
    }

    /**
     * Ids present in both frames that must be removed and re-added: parent changed, tag/text-node shape
     * changed, or relative order among siblings changed (everything outside the longest stable run).
     */
    private fun replacedIds(p: Index, n: Index, nextRoot: Wireframe): Set<Int> {
        val replaced = HashSet<Int>()
        for ((id, nw) in n.nodes) {
            val pw = p.nodes[id] ?: continue
            if (p.parent[id] != n.parent[id] || pw.type != nw.type || (pw.displayText() == null) != (nw.displayText() == null)) {
                replaced.add(id)
            }
        }
        markReordered(nextRoot, p, replaced)
        return replaced
    }

    private fun markReordered(parent: Wireframe, p: Index, replaced: MutableSet<Int>) {
        val kept = parent.children.filter { c ->
            c.id !in replaced && p.parent[c.id] == parent.id && p.nodes.containsKey(c.id)
        }
        if (kept.size > 1) {
            val keep = longestIncreasing(IntArray(kept.size) { p.position.getValue(kept[it].id) })
            kept.forEachIndexed { i, c -> if (i !in keep) replaced.add(c.id) }
        }
        parent.children.forEach { markReordered(it, p, replaced) }
    }

    /** Indices (into [seq], strictly increasing values) of one longest increasing subsequence. */
    private fun longestIncreasing(seq: IntArray): Set<Int> {
        val tailIdx = IntArray(seq.size)
        val link = IntArray(seq.size) { -1 }
        var len = 0
        for (i in seq.indices) {
            var lo = 0
            var hi = len
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (seq[tailIdx[mid]] < seq[i]) lo = mid + 1 else hi = mid
            }
            link[i] = if (lo > 0) tailIdx[lo - 1] else -1
            tailIdx[lo] = i
            if (lo == len) len++
        }
        val out = HashSet<Int>()
        var cur = if (len > 0) tailIdx[len - 1] else -1
        while (cur != -1) {
            out.add(cur)
            cur = link[cur]
        }
        return out
    }

    private fun collectRemoves(
        w: Wireframe,
        parentId: Int,
        p: Index,
        n: Index,
        replaced: Set<Int>,
        out: MutableList<Pair<Int, Int>>,
    ) {
        if (!n.nodes.containsKey(w.id) || w.id in replaced) {
            out.add(parentId to w.id)
            return
        }
        w.children.forEach { collectRemoves(it, w.id, p, n, replaced, out) }
    }

    @Suppress("LongParameterList")
    private fun collectNext(
        w: Wireframe,
        parentId: Int,
        anchor: Int?,
        p: Index,
        n: Index,
        replaced: Set<Int>,
        adds: MutableList<Pair<Int, Wireframe>>,
        addBefore: MutableList<Int?>,
        updates: MutableList<Wireframe>,
        texts: MutableList<Pair<Int, String>>,
    ) {
        val old = p.nodes[w.id]
        if (old == null || w.id in replaced) {
            adds.add(parentId to w)
            addBefore.add(anchor)
            return
        }
        if (changedStyle(old, w, p, n)) updates.add(w)
        val oldText = old.displayText()
        val newText = w.displayText()
        if (oldText != newText && newText != null) texts.add(w.id to newText)

        // Anchor for a new child = nearest following sibling that keeps its place (exists and is not replaced).
        val kids = w.children
        var nextStable: Int? = null
        val anchors = arrayOfNulls<Int>(kids.size)
        for (i in kids.indices.reversed()) {
            anchors[i] = nextStable
            val k = kids[i]
            if (p.nodes.containsKey(k.id) && k.id !in replaced) nextStable = k.id
        }
        kids.forEachIndexed { i, c -> collectNext(c, w.id, anchors[i], p, n, replaced, adds, addBefore, updates, texts) }
    }

    private fun changedStyle(a: Wireframe, b: Wireframe, p: Index, n: Index): Boolean =
        p.relX(a) != n.relX(b) || p.relY(a) != n.relY(b) ||
            a.width != b.width || a.height != b.height ||
            a.textColor != b.textColor || a.textSizeSp != b.textSizeSp || a.fontWeight != b.fontWeight ||
            a.visual != b.visual ||
            a.background != b.background || a.alpha != b.alpha || a.masked != b.masked
}
