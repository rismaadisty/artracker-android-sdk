package id.art.tracker.core

import java.util.Date

/** Pre-serialized JSON that [Json] writes verbatim. */
@JvmInline
internal value class RawJson(val json: String)

/** Minimal JSON encoder (no org.json / JsonWriter dependency). */
internal object Json {
    /** Default nesting limit for every item: deeper containers are written as the string `"[max_depth]"`. */
    const val MAX_DEPTH = 32

    /**
     * Limit for `replay_chunk` attributes only: rrweb snapshots nest about two JSON levels per wireframe level
     * (walker depth 50, Compose hosts add up to 50 more) plus the event/chunk wrappers. It still bounds cycles.
     */
    const val REPLAY_MAX_DEPTH = 256

    fun encode(value: Any?, maxDepth: Int = MAX_DEPTH): String =
        StringBuilder().also { write(value, it, maxDepth) }.toString()

    fun write(value: Any?, out: Appendable, maxDepth: Int = MAX_DEPTH) {
        writeValue(value, out, 0, maxDepth)
    }

    private fun writeValue(value: Any?, out: Appendable, depth: Int, maxDepth: Int) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value.toString())
            is Double -> writeFloating(value, value.isNaN() || value.isInfinite(), out)
            is Float -> writeFloating(value, value.isNaN() || value.isInfinite(), out)
            is Number -> writeNumber(value, out)
            is String -> writeString(value, out)
            is Char -> writeString(value.toString(), out)
            is Enum<*> -> writeString(value.name, out)
            is RawJson -> out.append(value.json)
            is Date -> writeString(Iso8601.format(value.time), out)
            else -> writeContainer(value, out, depth, maxDepth)
        }
    }

    /**
     * Int/Long/Short/Byte are written as is. Any other [Number] (atomics, `DoubleAdder`, a caller's own subclass)
     * only gets its text verbatim when that text is a valid JSON number: `NaN`, `Infinity` or anything else would
     * corrupt the whole payload, so it is written as a string instead.
     */
    private fun writeNumber(value: Number, out: Appendable) {
        if (value is Int || value is Long || value is Short || value is Byte) {
            out.append(value.toString())
            return
        }
        val text = value.toString()
        if (isJsonNumber(text)) out.append(text) else writeString(text, out)
    }

    /** `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?` without allocating. */
    private fun isJsonNumber(s: String): Boolean {
        var i = 0
        val n = s.length
        if (i < n && s[i] == '-') i++
        if (i >= n) return false
        if (s[i] == '0') {
            i++
        } else if (s[i] in '1'..'9') {
            while (i < n && s[i] in '0'..'9') i++
        } else {
            return false
        }
        if (i < n && s[i] == '.') {
            i++
            val start = i
            while (i < n && s[i] in '0'..'9') i++
            if (i == start) return false
        }
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < n && (s[i] == '+' || s[i] == '-')) i++
            val start = i
            while (i < n && s[i] in '0'..'9') i++
            if (i == start) return false
        }
        return i == n
    }

    private fun writeFloating(value: Number, invalid: Boolean, out: Appendable) {
        out.append(if (invalid) "null" else value.toString())
    }

    private fun writeContainer(value: Any, out: Appendable, depth: Int, maxDepth: Int) {
        if (depth >= maxDepth) {
            writeString("[max_depth]", out)
            return
        }
        when (value) {
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k.toString(), out)
                    out.append(':')
                    writeValue(v, out, depth + 1, maxDepth)
                }
                out.append('}')
            }
            is Iterable<*> -> writeSequence(value.iterator(), out, depth, maxDepth)
            is Array<*> -> writeSequence(value.iterator(), out, depth, maxDepth)
            is IntArray -> writeSequence(value.iterator(), out, depth, maxDepth)
            is LongArray -> writeSequence(value.iterator(), out, depth, maxDepth)
            is DoubleArray -> writeSequence(value.iterator(), out, depth, maxDepth)
            is FloatArray -> writeSequence(value.iterator(), out, depth, maxDepth)
            is ShortArray -> writeSequence(value.iterator(), out, depth, maxDepth)
            is ByteArray -> writeSequence(value.iterator(), out, depth, maxDepth)
            is BooleanArray -> writeSequence(value.iterator(), out, depth, maxDepth)
            is CharArray -> writeSequence(value.iterator(), out, depth, maxDepth)
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeSequence(it: Iterator<*>, out: Appendable, depth: Int, maxDepth: Int) {
        out.append('[')
        var first = true
        while (it.hasNext()) {
            if (!first) out.append(',')
            first = false
            writeValue(it.next(), out, depth + 1, maxDepth)
        }
        out.append(']')
    }

    /**
     * Unpaired surrogates are written as U+FFFD: they are not valid text (the UTF-8 body would turn them into `?`),
     * and SQLite's UTF-16 to UTF-8 conversion pairs a lone high surrogate with the NEXT unit, which would swallow the
     * closing quote of the string and corrupt the stored payload.
     */
    private fun writeString(s: String, out: Appendable) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' || c == '\u2028' || c == '\u2029' -> {
                    out.append("\\u")
                    val h = Integer.toHexString(c.code)
                    for (k in h.length until 4) out.append('0')
                    out.append(h)
                }
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        out.append(c).append(s[i + 1])
                        i++
                    } else {
                        out.append(REPLACEMENT)
                    }
                }
                Character.isLowSurrogate(c) -> out.append(REPLACEMENT)
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private const val REPLACEMENT = '\uFFFD'
}
