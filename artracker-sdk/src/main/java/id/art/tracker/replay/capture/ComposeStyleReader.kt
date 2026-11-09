package id.art.tracker.replay.capture

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import id.art.tracker.replay.model.TextAlignment
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.roundToInt

/** What the layout modifiers of one Compose node paint: fill, gradient, corners, border, shadow, clip, alpha. */
internal class ComposeBoxStyle(
    val background: Int? = null,
    val gradient: String? = null,
    val radiiDp: List<Float>? = null,
    val borderWidthDp: Float? = null,
    val borderColor: Int? = null,
    val shadowDp: Float? = null,
    val clip: Boolean = false,
    val alpha: Float = 1f,
)

/** Font size (sp), weight, color, italic, generic family, alignment, line height and wrapping of a text node. */
internal class ComposeTextStyle(
    val sizeSp: Float?,
    val weight: Int?,
    val color: Int?,
    val italic: Boolean,
    val fontFamily: String?,
    val align: TextAlignment?,
    val lineHeightDp: Float?,
    val wrap: Boolean,
)

/**
 * Reads how a Compose node looks, from public API only where one exists:
 * - text: `GetTextLayoutResult` -> `layoutInput.style` (never the text itself: a masked node must not reach it);
 * - shapes: `Shape.createOutline` (exact for rounded, circle and per-corner shapes);
 * - fills, borders, shadow, clip and painters: the `ModifierInfo` list of `layoutInfo`. Compose offers no public
 *   getter for them, so the modifier elements (`BackgroundElement`, `BorderModifierNodeElement`,
 *   `GraphicsLayerElement`, `ShadowGraphicsLayerElement`) are read by field name. A Compose version or an R8 pass that renames them just yields
 *   no style: nothing here ever throws.
 *
 * Compose is `compileOnly`: only [ComposeWalker] (itself behind `ClassAvailability.compose`) may use this object.
 */
internal object ComposeStyleReader {
    private val fields = ConcurrentHashMap<String, Field?>()

    fun boxStyle(info: LayoutInfo, density: Float): ComposeBoxStyle = try {
        readBox(info, density)
    } catch (_: Throwable) {
        ComposeBoxStyle()
    }

    /** A node's `Painter` (`Image`, `Icon`) and the `ColorFilter` that tints it. */
    class NodePainter(val painter: Painter, val colorFilter: ColorFilter?)

    /** The painter of the node's `PainterElement` modifier, or null when it paints none (or it cannot be read). */
    fun painterOf(info: LayoutInfo): NodePainter? = try {
        info.getModifierInfo().lastOrNull { it.modifier.javaClass.name.endsWith("PainterElement") }?.modifier?.let { m ->
            (field(m, "painter") as? Painter)?.let { NodePainter(it, field(m, "colorFilter") as? ColorFilter) }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Icon URI of a node's painter at [widthDp] x [heightDp], or null. Only vector painters, solid colors and
     * bitmaps of at most [IconRasterizer.MAX_ICON_DP] px are icons; any other painter (an image loader's, a custom
     * one) is arbitrary content and stays a placeholder.
     */
    fun iconUri(icons: IconRasterizer, p: NodePainter, widthDp: Int, heightDp: Int, dir: androidx.compose.ui.unit.LayoutDirection): String? = try {
        if (!isIconPainter(p.painter)) {
            null
        } else {
            val variant = "$widthDp x $heightDp|${p.colorFilter?.hashCode()}|$dir"
            icons.render(p.painter, variant, widthDp, heightDp) { androidCanvas ->
                CanvasDrawScope().draw(
                    Density(1f), dir, androidx.compose.ui.graphics.Canvas(androidCanvas),
                    Size(widthDp.toFloat(), heightDp.toFloat()),
                ) {
                    with(p.painter) { draw(size, 1f, p.colorFilter) }
                }
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun isIconPainter(p: Painter): Boolean = when (p.javaClass.name) {
        "androidx.compose.ui.graphics.vector.VectorPainter",
        "androidx.compose.ui.graphics.painter.ColorPainter",
        -> true
        "androidx.compose.ui.graphics.painter.BitmapPainter" -> (field(p, "image") as? ImageBitmap)?.let {
            it.width <= IconRasterizer.MAX_ICON_DP && it.height <= IconRasterizer.MAX_ICON_DP
        } == true
        else -> false
    }

    /** Bounds of [info] in the root's pixel space (clipped to the visible root), or null when not placed. */
    fun boundsOf(info: LayoutInfo): Rect? = try {
        if (info.isPlaced && info.isAttached) info.coordinates.boundsInRoot() else null
    } catch (_: Throwable) {
        null
    }

    private fun readBox(info: LayoutInfo, density: Float): ComposeBoxStyle {
        val size = Size(info.width.toFloat(), info.height.toFloat())
        val d = Density(density)
        var bg: Int? = null
        var gradient: String? = null
        var radii: List<Float>? = null
        var borderW: Float? = null
        var borderColor: Int? = null
        var elevation: Float? = null
        var clip = false
        var alpha = 1f
        var layerRadii: List<Float>? = null
        for (mi in info.getModifierInfo()) {
            val m = mi.modifier
            val name = m.javaClass.name
            when {
                name.endsWith("BackgroundElement") -> {
                    val brush = field(m, "brush") as? Brush
                    val colorRaw = field(m, "color") as? Long
                    val a = (field(m, "alpha") as? Float) ?: 1f
                    val color = colorRaw?.let { Color(it.toULong()) }?.takeIf { it.isSpecified }
                    when {
                        brush is SolidColor -> bg = withAlpha(brush.value.toArgb(), a)
                        brush != null -> gradientOf(brush)?.let { gradient = it; bg = null }
                        color != null -> { bg = withAlpha(color.toArgb(), a); gradient = null }
                    }
                    (field(m, "shape") as? Shape)?.let { s -> radiiOf(s, size, info.layoutDirection, d, density)?.let { radii = it } }
                }
                name.endsWith("BorderModifierNodeElement") -> {
                    val w = (field(m, "width") as? Float) ?: 0f
                    val brush = field(m, "brush") as? Brush
                    if (w > 0f) {
                        borderW = w
                        (brush as? SolidColor)?.let { borderColor = it.value.toArgb() }
                    }
                    if (radii == null) (field(m, "shape") as? Shape)?.let { s -> radii = radiiOf(s, size, info.layoutDirection, d, density) }
                }
                name.endsWith("ShadowGraphicsLayerElement") -> { // Modifier.shadow: elevation is already in dp
                    (field(m, "elevation") as? Float)?.takeIf { it > 0f }?.let { elevation = maxOf(elevation ?: 0f, it) }
                    if (field(m, "clip") == true) clip = true
                    (field(m, "shape") as? Shape)?.let { s -> layerRadii = radiiOf(s, size, info.layoutDirection, d, density) }
                }
                name.endsWith("GraphicsLayerElement") -> {
                    (field(m, "alpha") as? Float)?.let { alpha *= it.coerceIn(0f, 1f) }
                    (field(m, "shadowElevation") as? Float)?.takeIf { it > 0f }?.let { elevation = maxOf(elevation ?: 0f, it / density) }
                    if (field(m, "clip") == true) clip = true
                    (field(m, "shape") as? Shape)?.let { s -> layerRadii = radiiOf(s, size, info.layoutDirection, d, density) }
                }
            }
        }
        // A clip or shadow shape is the node's outline even without a fill (clipped images, cards).
        if (radii == null && (clip || elevation != null)) radii = layerRadii
        return ComposeBoxStyle(bg, gradient, radii, borderW?.takeIf { it > 0f }, borderColor, elevation, clip, alpha)
    }

    fun textStyle(config: SemanticsConfiguration, density: Float): ComposeTextStyle? = try {
        val results = ArrayList<TextLayoutResult>(1)
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        results.firstOrNull()?.let { result ->
            val style = result.layoutInput.style
            val size = style.fontSize
            val color = style.color
            val lineHeight = style.lineHeight
            ComposeTextStyle(
                sizeSp = if (size.isSp && size.value.isFinite() && size.value > 0f) size.value else null,
                weight = style.fontWeight?.weight?.takeIf { it != REGULAR },
                color = if (color.isSpecified) color.toArgb() else null,
                italic = style.fontStyle == FontStyle.Italic,
                fontFamily = familyOf(style.fontFamily),
                align = alignOf(style.textAlign),
                lineHeightDp = if (lineHeight.isSp && lineHeight.value.isFinite() && lineHeight.value > 0f) lineHeight.value else null,
                wrap = result.lineCount > 1,
            )
        }
    } catch (_: Throwable) {
        null
    }

    private fun familyOf(f: FontFamily?): String? = when (f) {
        FontFamily.SansSerif -> "sans-serif"
        FontFamily.Serif -> "serif"
        FontFamily.Monospace -> "monospace"
        FontFamily.Cursive -> "cursive"
        else -> null // Default, or an app font whose name Compose does not expose
    }

    private fun alignOf(a: TextAlign): TextAlignment? = when (a) {
        TextAlign.Center -> TextAlignment.CENTER
        TextAlign.End, TextAlign.Right -> TextAlignment.RIGHT
        TextAlign.Justify -> TextAlignment.JUSTIFY
        else -> null
    }

    /** Corner radii (dp) in CSS order, or null for a plain rectangle / a shape that is not rounded. */
    private fun radiiOf(shape: Shape, size: Size, dir: androidx.compose.ui.unit.LayoutDirection, d: Density, density: Float): List<Float>? {
        if (size.width <= 0f || size.height <= 0f) return null
        val outline = try {
            shape.createOutline(size, dir, d)
        } catch (_: Throwable) {
            return null
        }
        val rr = (outline as? Outline.Rounded)?.roundRect ?: return null
        return listOf(
            rr.topLeftCornerRadius.x, rr.topRightCornerRadius.x, rr.bottomRightCornerRadius.x, rr.bottomLeftCornerRadius.x,
        ).map { it / density }.takeIf { r -> r.any { it > 0f } }
    }

    /**
     * `Brush.linearGradient`/`verticalGradient`/`horizontalGradient` -> a CSS `linear-gradient`. The brush class is
     * internal, so colors, stops and the start/end offsets are read by field name; radial and sweep brushes are not
     * linear and give null.
     */
    private fun gradientOf(brush: Brush): String? {
        if (!brush.javaClass.name.endsWith("LinearGradient")) return null
        val colors = (field(brush, "colors") as? List<*>)?.filterIsInstance<Color>() ?: return null
        if (colors.size < 2) return null
        val start = field(brush, "start") as? Long ?: return null
        val end = field(brush, "end") as? Long ?: return null
        var dx = offsetX(end) - offsetX(start)
        var dy = offsetY(end) - offsetY(start)
        // An unbounded end (Float.POSITIVE_INFINITY) only gives a direction.
        if (dx.isInfinite() || dy.isInfinite()) {
            dx = if (dx.isInfinite()) 1f else if (dy.isInfinite()) 0f else dx
            dy = if (dy.isInfinite()) 1f else if (dx.isInfinite()) 0f else dy
        }
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0f && dy == 0f)) return null
        val degrees = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).roundToInt()
        val angle = ((degrees % 360) + 360) % 360
        val stops = (field(brush, "stops") as? List<*>)?.filterIsInstance<Float>()?.takeIf { it.size == colors.size }
        val parts = colors.mapIndexed { i, c ->
            val css = DrawableStyleReader.cssColor(c.toArgb())
            if (stops == null) css else "$css ${(stops[i].coerceIn(0f, 1f) * 100).roundToInt()}%"
        }
        return "linear-gradient(${angle}deg," + parts.joinToString(",") + ")"
    }

    private fun offsetX(packed: Long): Float = Float.fromBits((packed shr 32).toInt())
    private fun offsetY(packed: Long): Float = Float.fromBits(packed.toInt())

    private fun withAlpha(argb: Int, alpha: Float): Int {
        if (alpha >= 1f) return argb
        val a = (((argb ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /** Value of the field [name] declared by [target]'s class or a superclass; null when absent or unreadable. */
    internal fun field(target: Any, name: String): Any? {
        val cls = target.javaClass
        val f = fields.getOrPut(cls.name + "#" + name) {
            var c: Class<*>? = cls
            var found: Field? = null
            while (c != null && found == null) {
                found = runCatching { c!!.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                c = c.superclass
            }
            found
        }
        return try {
            f?.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    private val childrenGetters = ConcurrentHashMap<Class<*>, java.lang.reflect.Method?>()

    /**
     * The layout children of [info]. Compose's public `LayoutInfo` has no children accessor, so this calls the
     * `LayoutNode` getter by name; any failure (other Compose version, R8) gives an empty list and the walker
     * simply sees the semantics nodes only.
     */
    fun layoutChildren(info: LayoutInfo): List<LayoutInfo> = try {
        val getter = childrenGetters.getOrPut(info.javaClass) {
            info.javaClass.methods.firstOrNull {
                it.name.startsWith("getChildren") && it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType)
            }
        }
        (getter?.invoke(info) as? List<*>)?.filterIsInstance<LayoutInfo>().orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    private const val REGULAR = 400
}
