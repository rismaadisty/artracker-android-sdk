package id.art.tracker.core

import java.security.SecureRandom
import java.util.Random

internal object UuidV7 {
    private val secureRandom = SecureRandom()

    fun generate(nowMs: Long = System.currentTimeMillis(), random: Random = secureRandom): String {
        val randA = random.nextInt() and 0xFFF
        val randB = random.nextLong() and 0x3FFFFFFFFFFFFFFFL
        val msb = ((nowMs and 0xFFFFFFFFFFFFL) shl 16) or (0x7L shl 12) or randA.toLong()
        val lsb = (0x2L shl 62) or randB
        val hex = hex16(msb) + hex16(lsb)
        return buildString(36) {
            append(hex, 0, 8).append('-')
            append(hex, 8, 12).append('-')
            append(hex, 12, 16).append('-')
            append(hex, 16, 20).append('-')
            append(hex, 20, 32)
        }
    }

    private fun hex16(v: Long): String {
        val s = java.lang.Long.toHexString(v)
        return "0".repeat(16 - s.length) + s
    }
}
