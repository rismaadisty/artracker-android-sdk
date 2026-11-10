package id.art.tracker.replay.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import id.art.tracker.QualityPreset
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * The processing-thread half of a screenshot: paints the masks over the full-size pixels FIRST, then fingerprints
 * (64x64 CRC, so a screen identical to the last one is dropped), scales to the quality preset (the short side, never
 * upscaled) and compresses to JPEG. What sits under a mask therefore never reaches a scaled copy, a fingerprint or
 * the encoder. Not thread-safe: the processing context only.
 */
internal class ScreenshotEncoder(
    private val painter: MaskPainter = MaskPainter(),
    private val duplicates: DuplicateFilter = DuplicateFilter(),
) {
    /** Null when the frame equals the previous one and [force] is false (the fingerprint is still updated). */
    fun encode(raw: RawFrame, preset: QualityPreset, screen: String, density: Float, force: Boolean): ScreenFrame? {
        val source = raw.bitmap
        val canvas = Canvas(source)
        for (m in raw.masks) painter.paintMask(canvas, m)
        raw.keyboard?.let { painter.paintKeyboard(canvas, it) }

        if (duplicates.isDuplicate(fingerprint(source)) && !force) return null

        val short = minOf(source.width, source.height)
        val target = minOf(short, preset.shortSidePx)
        val scaled = if (target < short) {
            val factor = target.toDouble() / short
            Bitmap.createScaledBitmap(
                source, (source.width * factor).roundToInt().coerceAtLeast(1), (source.height * factor).roundToInt().coerceAtLeast(1), true,
            )
        } else {
            source
        }
        try {
            val out = ByteArrayOutputStream(scaled.width * scaled.height / 8 + 1024)
            check(scaled.compress(Bitmap.CompressFormat.JPEG, preset.jpegQuality, out)) { "jpeg compression failed" }
            val d = if (density > 0f) density else 1f
            return ScreenFrame(
                ts = raw.ts, screen = screen, width = scaled.width, height = scaled.height,
                viewportWidthDp = (source.width / d).roundToInt(), viewportHeightDp = (source.height / d).roundToInt(),
                jpeg = out.toByteArray(), masked = raw.masks.size,
            )
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    /** The next frame counts as new (after a restart or a forced snapshot). */
    fun resetDuplicates() = duplicates.reset()

    private fun fingerprint(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, FINGERPRINT_SIDE, FINGERPRINT_SIDE, true)
        try {
            val pixels = IntArray(FINGERPRINT_SIDE * FINGERPRINT_SIDE)
            small.getPixels(pixels, 0, FINGERPRINT_SIDE, 0, 0, FINGERPRINT_SIDE, FINGERPRINT_SIDE)
            return DuplicateFilter.crc(pixels)
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    private companion object {
        const val FINGERPRINT_SIDE = 64
    }
}
