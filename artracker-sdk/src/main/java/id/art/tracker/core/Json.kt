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
            is Number -> out.append(value.toString())
            is String -> writeString(value, out)
            is Char -> writeString(value.toString(), out)
            is Enum<*> -> writeString(value.name, out)
            is RawJson -> out.append(value.json)
            is Date -> writeString(Iso8601.format(value.time), out)
            else -> writeContainer(value, out, depth, maxDepth)
        }
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
