package id.art.tracker.replay.model

/** Kind of node the view walker recorded. */
internal enum class WireframeType { CONTAINER, TEXT, INPUT, BUTTON, IMAGE, WEB, SURFACE, COMPOSE_BLOCK }

/**
 * One node of a captured UI frame. Coordinates and sizes are in dp, relative to the screen.
 *
 * [text] is already masked by the walker when [masked] is true; the raw string of a masked node
 * must never be stored here.
 */
internal data class Wireframe(
    val id: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val type: WireframeType,
    val text: String? = null,
    val textColor: Int? = null,
    val textSizeSp: Float? = null,
    val background: Int? = null,
    val alpha: Float = 1f,
    val masked: Boolean = false,
    val summarized: Boolean = false,
    val children: List<Wireframe> = emptyList(),
)

/** Every field of the node and its descendants, for tests asserting no raw text leaks. */
internal fun Wireframe.toDebugString(): String = buildString { appendDebug(this@toDebugString) }

private fun StringBuilder.appendDebug(w: Wireframe) {
    append("Wireframe(id=").append(w.id)
        .append(", x=").append(w.x).append(", y=").append(w.y)
        .append(", width=").append(w.width).append(", height=").append(w.height)
        .append(", type=").append(w.type)
        .append(", text=").append(w.text)
        .append(", textColor=").append(w.textColor)
        .append(", textSizeSp=").append(w.textSizeSp)
        .append(", background=").append(w.background)
        .append(", alpha=").append(w.alpha)
        .append(", masked=").append(w.masked)
        .append(", summarized=").append(w.summarized)
        .append(", children=[")
    w.children.forEachIndexed { i, c ->
        if (i > 0) append(", ")
        appendDebug(c)
    }
    append("])")
}
