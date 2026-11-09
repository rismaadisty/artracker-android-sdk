package id.art.tracker.replay.model

/** Kind of node the view walker recorded. */
internal enum class WireframeType { CONTAINER, TEXT, INPUT, BUTTON, IMAGE, WEB, SURFACE, COMPOSE_BLOCK }

/** Horizontal alignment of a node's text. */
internal enum class TextAlignment(val css: String, val flex: String) {
    LEFT("left", "flex-start"),
    CENTER("center", "center"),
    RIGHT("right", "flex-end"),
    JUSTIFY("justify", "flex-start"),
}

/** How an [VisualStyle.image] fills its box (CSS `background-size`). */
internal enum class ImageFit(val css: String) { CONTAIN("contain"), COVER("cover"), FILL("100% 100%") }

/**
 * Visual properties beyond the basic color/size ones: everything the web player needs to draw the node like the app.
 * All lengths are dp. Only [Wireframe.displayText]-bearing nodes use the text fields. Never carries user text.
 *
 * [image] is a complete `data:image/...;base64,` URI of an already rasterized ICON (never a photo; see the walkers),
 * [gradient] a ready CSS `linear-gradient(...)` value built from numbers only.
 */
internal data class VisualStyle(
    val gradient: String? = null,
    /** Corner radii in CSS order: top-left, top-right, bottom-right, bottom-left. */
    val radiiDp: List<Float>? = null,
    val borderWidthDp: Float? = null,
    val borderColor: Int? = null,
    val shadowDp: Float? = null,
    val clip: Boolean = false,
    /** A CSS generic family (`sans-serif`, `serif`, `monospace`, `cursive`) or null for the player default. */
    val fontFamily: String? = null,
    val italic: Boolean = false,
    val textAlign: TextAlignment? = null,
    val lineHeightDp: Float? = null,
    /** Multi-line text: the player must wrap instead of the default single line. */
    val wrap: Boolean = false,
    val image: String? = null,
    val imageFit: ImageFit? = null,
    /** Padding (top, right, bottom, left) that keeps the [image] inside the content box, as the view lays it out. */
    val paddingDp: List<Float>? = null,
)

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
    /** CSS font weight (100..900) when the source exposes it, else null. */
    val fontWeight: Int? = null,
    val background: Int? = null,
    val alpha: Float = 1f,
    val masked: Boolean = false,
    val summarized: Boolean = false,
    val visual: VisualStyle? = null,
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
        .append(", fontWeight=").append(w.fontWeight)
        .append(", background=").append(w.background)
        .append(", alpha=").append(w.alpha)
        .append(", masked=").append(w.masked)
        .append(", summarized=").append(w.summarized)
        .append(", visual=").append(w.visual)
        .append(", children=[")
    w.children.forEachIndexed { i, c ->
        if (i > 0) append(", ")
        appendDebug(c)
    }
    append("])")
}
