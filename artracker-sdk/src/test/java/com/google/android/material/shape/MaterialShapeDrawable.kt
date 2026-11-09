package com.google.android.material.shape

import android.content.res.ColorStateList
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Test double with the same fully qualified name and getters as Material Components' `MaterialShapeDrawable`.
 * The SDK reads that class by name (Material is not a dependency of the SDK), so the real library is not needed to
 * prove the reflective path.
 */
@Suppress("unused")
class MaterialShapeDrawable(
    private val fill: ColorStateList?,
    private val stroke: ColorStateList?,
    private val strokeW: Float,
    private val tl: Float,
    private val tr: Float,
    private val br: Float,
    private val bl: Float,
) : Drawable() {
    fun getFillColor(): ColorStateList? = fill
    fun getStrokeColor(): ColorStateList? = stroke
    fun getStrokeWidth(): Float = strokeW
    fun getTopLeftCornerResolvedSize(): Float = tl
    fun getTopRightCornerResolvedSize(): Float = tr
    fun getBottomRightCornerResolvedSize(): Float = br
    fun getBottomLeftCornerResolvedSize(): Float = bl

    override fun draw(canvas: android.graphics.Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
