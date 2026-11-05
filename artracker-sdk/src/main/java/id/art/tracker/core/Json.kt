package id.art.tracker.core

import java.util.Date

/** Pre-serialized JSON that [Json] writes verbatim. */
@JvmInline
internal value class RawJson(val json: String)

/** Minimal JSON encoder (no org.json / JsonWriter dependency). */
internal object Json {
    const val MAX_DEPTH = 32

    fun encode(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    fun write(value: Any?, out: Appendable) {
        writeValue(value, out, 0)
    }

    private fun writeValue(value: Any?, out: Appendable, depth: Int) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value.toString())
            is Double -> writeFloating(value, value.isNaN() || value.isInfinite(), out)
            is Float -> writeFloating(value, value.isNaN() || value.isInfinite(), out)
            is Number -> out.append(value.toString())
            is String -> writeString(value, out)
            is Char -> writeString(value.toString(), out)
            is Enum<*> -> writeString(value.name, out)
            is RawJson -> out.append(value.json)
            is Date -> writeString(Iso8601.format(value.time), out)
            else -> writeContainer(value, out, depth)
        }
    }

    private fun writeFloating(value: Number, invalid: Boolean, out: Appendable) {
        out.append(if (invalid) "null" else value.toString())
    }

    private fun writeContainer(value: Any, out: Appendable, depth: Int) {
        if (depth >= MAX_DEPTH) {
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
                    writeValue(v, out, depth + 1)
                }
                out.append('}')
            }
            is Iterable<*> -> writeSequence(value.iterator(), out, depth)
            is Array<*> -> writeSequence(value.iterator(), out, depth)
            is IntArray -> writeSequence(value.iterator(), out, depth)
            is LongArray -> writeSequence(value.iterator(), out, depth)
            is DoubleArray -> writeSequence(value.iterator(), out, depth)
            is FloatArray -> writeSequence(value.iterator(), out, depth)
            is ShortArray -> writeSequence(value.iterator(), out, depth)
            is ByteArray -> writeSequence(value.iterator(), out, depth)
            is BooleanArray -> writeSequence(value.iterator(), out, depth)
            is CharArray -> writeSequence(value.iterator(), out, depth)
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeSequence(it: Iterator<*>, out: Appendable, depth: Int) {
        out.append('[')
        var first = true
        while (it.hasNext()) {
            if (!first) out.append(',')
            first = false
            writeValue(it.next(), out, depth + 1)
        }
        out.append(']')
    }

    private fun writeString(s: String, out: Appendable) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' || c == ' ' || c == ' ' -> {
                    out.append("\\u")
                    val h = Integer.toHexString(c.code)
                    for (i in h.length until 4) out.append('0')
                    out.append(h)
                }
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
