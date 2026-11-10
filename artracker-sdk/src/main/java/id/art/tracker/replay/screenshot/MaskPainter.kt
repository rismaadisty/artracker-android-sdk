package id.art.tracker.replay.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.BitmapShader

/**
 * Paints the opaque cross-stripe pattern over a masked region (so the dashboard shows "hidden", not a blank hole),
 * and the neutral keyboard placeholder. Draws on the full-size, uncompressed bitmap before scaling and encoding.
 * Not thread-safe: the processing context only.
 */
internal class MaskPainter {
    private val maskPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        shader = BitmapShader(patternTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val keyboardPaint = Paint().apply { color = KEYBOARD_GREY; isAntiAlias = false }
    private val labelPaint = Paint().apply {
        color = KEYBOARD_LABEL
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    fun paintMask(canvas: Canvas, rect: MaskRect) {
        canvas.save()
        canvas.clipRect(Rect(rect.left, rect.top, rect.right, rect.bottom))
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), maskPaint)
        canvas.restore()
    }

    fun paintKeyboard(canvas: Canvas, rect: MaskRect) {
        canvas.save()
        canvas.clipRect(Rect(rect.left, rect.top, rect.right, rect.bottom))
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), keyboardPaint)
        labelPaint.textSize = ((rect.bottom - rect.top) * LABEL_HEIGHT_FRACTION).coerceIn(12f, 64f)
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText(KEYBOARD_LABEL_TEXT, cx, cy, labelPaint)
        canvas.restore()
    }

    private fun patternTile(): Bitmap {
        val tile = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        for (y in 0 until TILE) for (x in 0 until TILE) {
            val stripe = (x + y) % TILE < STRIPE_WIDTH || (x - y + TILE) % TILE < STRIPE_WIDTH
            tile.setPixel(x, y, if (stripe) MASK_STRIPE else MASK_BASE)
        }
        return tile
    }

    companion object {
        val MASK_BASE: Int = 0xFFBDBDBD.toInt()
        val MASK_STRIPE: Int = 0xFF757575.toInt()
        val KEYBOARD_GREY: Int = 0xFFE0E0E0.toInt()
        private val KEYBOARD_LABEL: Int = 0xFF616161.toInt()
        private const val KEYBOARD_LABEL_TEXT = "keyboard"
        private const val LABEL_HEIGHT_FRACTION = 0.12f
        private const val TILE = 16
        private const val STRIPE_WIDTH = 3
    }
}
