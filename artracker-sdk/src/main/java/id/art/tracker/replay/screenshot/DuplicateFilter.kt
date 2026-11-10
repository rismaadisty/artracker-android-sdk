package id.art.tracker.replay.screenshot

import java.util.zip.CRC32

/**
 * Suppresses a frame identical to the previous one. The fingerprint is the CRC32 of a 64x64 downscale of the frame
 * (see [crc]); the capturer builds the downscale. Not thread-safe: the processing context only.
 */
internal class DuplicateFilter {
    private var last: Int? = null

    /** True when [fingerprint] equals the last one seen; otherwise remembers it and returns false. */
    fun isDuplicate(fingerprint: Int): Boolean {
        if (last == fingerprint) return true
        last = fingerprint
        return false
    }

    /** The next frame counts as new (after a forced frame, a screen change or a restart). */
    fun reset() {
        last = null
    }

    companion object {
        /** CRC32 of the ARGB ints of a small downscaled frame. */
        fun crc(pixels: IntArray): Int {
            val crc = CRC32()
            val buf = ByteArray(4)
            for (p in pixels) {
                buf[0] = (p ushr 24).toByte()
                buf[1] = (p ushr 16).toByte()
                buf[2] = (p ushr 8).toByte()
                buf[3] = p.toByte()
                crc.update(buf)
            }
            return crc.value.toInt()
        }
    }
}
