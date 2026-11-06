package id.art.tracker.testutil

/**
 * Strict RFC 8259 validator. `org.json` (Android's and the JSON.org one) is lenient: it accepts `NaN`, unquoted
 * strings, single quotes, trailing commas and so on, so it cannot prove that the server's parser will accept a payload.
 */
internal object StrictJson {
    /** Returns null when [text] is one valid JSON value, otherwise a message with the offset of the problem. */
    fun validate(text: String, maxDepth: Int = 512): String? = try {
        Parser(text, maxDepth).parseDocument()
        null
    } catch (e: IllegalArgumentException) {
        e.message
    }

    private class Parser(private val s: String, private val maxDepth: Int) {
        private var i = 0

        fun parseDocument() {
            skipWs()
            value(0)
            skipWs()
            if (i != s.length) fail("trailing content")
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at offset $i")

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun value(depth: Int) {
            if (depth > maxDepth) fail("too deep")
            if (i >= s.length) fail("unexpected end")
            when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
        }

        private fun obj(depth: Int) {
            i++
            skipWs()
            if (peek() == '}') {
                i++
                return
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("object key must be a string")
                string()
                skipWs()
                if (peek() != ':') fail("expected ':'")
                i++
                skipWs()
                value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int) {
            i++
            skipWs()
            if (peek() == ']') {
                i++
                return
            }
            while (true) {
                skipWs()
                value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end")

        private fun string() {
            i++ // opening quote
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return
                    c < ' ' -> fail("raw control character U+%04X".format(c.code))
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (s[i++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> {
                                repeat(4) {
                                    if (i >= s.length || s[i].digitToIntOrNull(16) == null) fail("bad \\u escape")
                                    i++
                                }
                            }
                            else -> fail("bad escape")
                        }
                    }
                }
            }
        }

        private fun number() {
            if (peek() == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                fail("bad number")
            }
            if (i < s.length && s[i] == '.') {
                i++
                digits()
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                digits()
            }
        }

        private fun digits() {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == start) fail("bad number")
        }
    }
}
