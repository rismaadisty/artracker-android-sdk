package id.art.tracker.replay.capture

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import java.util.Locale

/** The shape, fill and border of a view background, all lengths in dp. */
internal class DrawableLook(
    val color: Int? = null,
    val gradient: String? = null,
    val radiiDp: List<Float>? = null,
    val borderWidthDp: Float? = null,
    val borderColor: Int? = null,
)

/**
 * Reads the visible look of a view background (`ColorDrawable`, `GradientDrawable`, Material's `MaterialShapeDrawable`,
 * and the `Ripple`/`Layer`/`StateList`/`Inset` wrappers around them). Main thread, read-only, never throws.
 *
 * `MaterialShapeDrawable` is found by class name because Material Components is not a dependency of the SDK.
 * `GradientDrawable` has no stroke getter, so the stroke comes from its hidden state by reflection; a platform that
 * hides it just loses the border.
 */
internal object DrawableStyleReader {
    private const val MAX_DEPTH = 4
    private const val MATERIAL_SHAPE = "com.google.android.material.shape.MaterialShapeDrawable"

    fun read(view: View, density: Float): DrawableLook? = try {
        val drawable = view.background
        val look = drawable?.let { readDrawable(it, view.drawableState, density, 0) }
        val tint = view.backgroundTintList?.getColorForState(view.drawableState, 0)
        if (look != null && tint != null && drawable is ColorDrawable) {
            DrawableLook(color = tint)
        } else {
            look
        }
    } catch (_: Throwable) {
        null
    }

    private fun readDrawable(d: Drawable, state: IntArray, density: Float, depth: Int): DrawableLook? {
        if (depth > MAX_DEPTH) return null
        return when {
            d is ColorDrawable -> DrawableLook(color = d.color)
            d is GradientDrawable -> readGradient(d, state, density)
            isMaterialShape(d) -> readMaterial(d, state, density)
            d is DrawableContainer -> d.current?.let { readDrawable(it, state, density, depth + 1) }
            d is InsetDrawable -> d.drawable?.let { readDrawable(it, state, density, depth + 1) }
            // RippleDrawable is a LayerDrawable: its first layer is the content, the mask (if any) comes after it.
            d is LayerDrawable -> {
                var found: DrawableLook? = null
                for (i in 0 until d.numberOfLayers) {
                    val layer = d.getDrawable(i) ?: continue
                    found = readDrawable(layer, state, density, depth + 1)
                    if (found != null) break
                }
                found
            }
            else -> null
        }
    }

    private fun readGradient(d: GradientDrawable, state: IntArray, density: Float): DrawableLook {
        val colors = d.colors
        val solid = d.color?.getColorForState(state, d.color?.defaultColor ?: 0)
        val gradient = if (colors != null && colors.size >= 2 && d.gradientType == GradientDrawable.LINEAR_GRADIENT) {
            linearGradient(d.orientation, colors)
        } else {
            null
        }
        val radii = d.cornerRadii?.takeIf { it.size >= 8 }?.let {
            listOf(it[0], it[2], it[4], it[6]).map { r -> r / density }
        } ?: d.cornerRadius.takeIf { it > 0f }?.let { r -> List(4) { r / density } }
        val stroke = strokeOf(d)
        return DrawableLook(
            color = if (gradient == null) solid else null,
            gradient = gradient,
            radiiDp = radii,
            borderWidthDp = stroke?.first?.div(density),
            borderColor = stroke?.second,
        )
    }

    /** `GradientDrawable.mGradientState.mStrokeWidth` / `mStrokeColors`: no public getter exists. */
    private fun strokeOf(d: GradientDrawable): Pair<Float, Int>? = try {
        val state = d.constantState ?: return null
        fun field(name: String) = state.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(state)
        val width = (field("mStrokeWidth") as? Int)?.toFloat() ?: 0f
        val colors = field("mStrokeColors") as? ColorStateList
        if (width > 0f && colors != null) width to colors.defaultColor else null
    } catch (_: Throwable) {
        null
    }

    private fun isMaterialShape(d: Drawable): Boolean {
        var c: Class<*>? = d.javaClass
        while (c != null) {
            if (c.name == MATERIAL_SHAPE) return true
            c = c.superclass
        }
        return false
    }

    private fun readMaterial(d: Drawable, state: IntArray, density: Float): DrawableLook? = try {
        fun call(name: String): Any? = d.javaClass.getMethod(name).invoke(d)
        val fill = (call("getFillColor") as? ColorStateList)?.let { it.getColorForState(state, it.defaultColor) }
        val stroke = (call("getStrokeColor") as? ColorStateList)?.let { it.getColorForState(state, it.defaultColor) }
        val strokeWidth = (call("getStrokeWidth") as? Float)?.div(density)
        val radii = listOf(
            "getTopLeftCornerResolvedSize", "getTopRightCornerResolvedSize",
            "getBottomRightCornerResolvedSize", "getBottomLeftCornerResolvedSize",
        ).map { (call(it) as? Float ?: 0f) / density }
        DrawableLook(
            color = fill, radiiDp = radii.takeIf { r -> r.any { it > 0f } },
            borderWidthDp = strokeWidth?.takeIf { it > 0f && stroke != null }, borderColor = stroke,
        )
    } catch (_: Throwable) {
        null
    }

    private fun linearGradient(o: GradientDrawable.Orientation, colors: IntArray): String {
        val angle = when (o) {
            GradientDrawable.Orientation.TOP_BOTTOM -> 180
            GradientDrawable.Orientation.TR_BL -> 225
            GradientDrawable.Orientation.RIGHT_LEFT -> 270
            GradientDrawable.Orientation.BR_TL -> 315
            GradientDrawable.Orientation.BOTTOM_TOP -> 0
            GradientDrawable.Orientation.BL_TR -> 45
            GradientDrawable.Orientation.LEFT_RIGHT -> 90
            GradientDrawable.Orientation.TL_BR -> 135
        }
        return "linear-gradient(${angle}deg," + colors.joinToString(",") { cssColor(it) } + ")"
    }

    /** `#rrggbb`, or `rgba(r,g,b,a)` when translucent. Locale-independent. */
    fun cssColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (a == 0xFF) return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b)
        val alpha = String.format(Locale.ROOT, "%.3f", a / 255f).trimEnd('0').trimEnd('.')
        return "rgba($r,$g,$b,$alpha)"
    }
}
