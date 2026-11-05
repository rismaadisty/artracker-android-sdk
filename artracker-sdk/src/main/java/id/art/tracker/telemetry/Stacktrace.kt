package id.art.tracker.telemetry

import java.security.MessageDigest

/** Stack trace text and fingerprint of a [Throwable] (ARCHITECTURE.md section 8.1). Pure and allocation-lean. */
internal object Stacktrace {
    const val MAX_BYTES = 16_384
    private const val MAX_CAUSES = 8
    private const val MAX_LINE_CHARS = 1024
    private const val FINGERPRINT_FRAMES = 5
    private const val TRUNCATION_MARK = "\t... [truncated]"

    /**
     * `Class: message` + `\tat frame` lines, followed by `Caused by: ...` for each cause (at most [MAX_CAUSES],
     * cycles cut). Never longer than [maxBytes] in UTF-8: when the budget runs out the text ends with a
     * truncation marker. Work stops at the budget, so a 5000-frame trace costs about as much as a 300-frame one.
     */
    fun format(t: Throwable, maxBytes: Int = MAX_BYTES): String {
        val markBytes = utf8Size(TRUNCATION_MARK)
        val budget = maxBytes - markBytes - 1
        val sb = StringBuilder(minOf(maxBytes, 4096))
        var used = 0
        var truncated = false

        fun append(line: String): Boolean {
            val clipped = if (line.length > MAX_LINE_CHARS) clipLine(line) else line
            val size = utf8Size(clipped) + 1
            if (used + size > budget) return false
            sb.append(clipped).append('\n')
            used += size
            return true
        }

        val seen = ArrayList<Throwable>(4)
        var current: Throwable? = t
        var depth = 0
        outer@ while (current != null) {
            if (seen.any { it === current }) break // cyclic cause chain
            if (depth > MAX_CAUSES) {
                truncated = true
                break
            }
            seen += current
            val header = if (depth == 0) describe(current) else "Caused by: " + describe(current)
            if (!append(header)) {
                truncated = true
                break
            }
            val frames = try {
                current.stackTrace
            } catch (_: Throwable) {
                emptyArray()
            }
            for (frame in frames) {
                if (!append("\tat $frame")) {
                    truncated = true
                    break@outer
                }
            }
            current = try {
                current.cause
            } catch (_: Throwable) {
                null
            }
            depth++
        }
        if (truncated) sb.append(TRUNCATION_MARK) else if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /** `Class: message`, or just `Class` without a message. Never throws. */
    fun summary(t: Throwable): String {
        val name = t.javaClass.name
        val message = try {
            t.message
        } catch (_: Throwable) {
            null
        }
        return if (message.isNullOrEmpty()) name else "$name: $message"
    }

    /**
     * SHA-1 (hex) of the exception class name and its [FINGERPRINT_FRAMES] top frames as `className.method`.
     * The message and line numbers are left out on purpose, so the same bug reported with different data (or
     * after an unrelated edit of the file) groups together.
     */
    fun fingerprint(t: Throwable): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(t.javaClass.name.toByteArray(Charsets.UTF_8))
        val frames = try {
            t.stackTrace
        } catch (_: Throwable) {
            emptyArray()
        }
        for (i in 0 until minOf(FINGERPRINT_FRAMES, frames.size)) {
            val f = frames[i]
            md.update('\n'.code.toByte())
            md.update((f.className + "." + f.methodName).toByteArray(Charsets.UTF_8))
        }
        val digest = md.digest()
        val hex = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hex[i * 2] = HEX[v ushr 4]
            hex[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(hex)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun describe(t: Throwable): String = try {
        t.toString()
    } catch (_: Throwable) {
        t.javaClass.name
    }

    private fun clipLine(line: String): String {
        val end = if (Character.isHighSurrogate(line[MAX_LINE_CHARS - 1])) MAX_LINE_CHARS - 1 else MAX_LINE_CHARS
        return line.substring(0, end)
    }

    /** UTF-8 size in bytes; an unpaired surrogate is counted as 3 (an over-estimate, never an under-estimate). */
    fun utf8Size(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return n
    }
}
