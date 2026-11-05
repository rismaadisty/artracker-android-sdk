package id.art.tracker.privacy

import id.art.tracker.core.Iso8601
import id.art.tracker.core.Json
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap

/**
 * Port of the web SDK `redactor.ts` (built-in redaction + key deny-list + walk limits), plus the Android-only
 * 24 KB attribute budget. Patterns are kept identical; JS-vs-Java regex differences are handled inline:
 * `\b` is ASCII-only in JS, `\s` is the ECMAScript whitespace set, `$` only matches at the very end.
 */
internal object AttributeRedactor {
    const val REDACTED = "[redacted]"
    const val MAX_DEPTH = 6
    const val MAX_NODES = 1000
    const val MAX_BYTES = 24 * 1024

    private const val TRUNCATED_KEY = "_truncated"
    private const val MARKER_DEPTH = "[max-depth]"
    private const val MARKER_CIRCULAR = "[circular]"
    private const val MARKER_NODES = "[truncated]"

    private const val WS = UrlScrubber.JS_WS
    // ASCII-only equivalents of JS `\b` for a pattern edge that is a word character.
    private const val B_START = "(?<![A-Za-z0-9_])"
    private const val B_END = "(?![A-Za-z0-9_])"

    private val JWT_RE = Regex("""eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")
    private val BEARER_RE = Regex("""${B_START}Bearer[$WS]+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)
    private val BASIC_RE = Regex("""${B_START}Basic[$WS]+[A-Za-z0-9+/=]{8,}""")
    private val API_KEY_RES = listOf(
        Regex("""$B_START(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{8,}$B_END"""),
        Regex("""${B_START}art_(?:sec|live)_[A-Za-z0-9]{8,}$B_END"""),
    )
    private val EMAIL_RE = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val CARD_RE = Regex("""(^|\D)((?:\d[ -]?){12,18}\d)(?!\d)""")
    private val NIK_RE = Regex("""(^|\D)(\d{16})(?!\d)""")
    private val PHONE_RE = Regex("""(^|\D)((?:\+62|62|0)8\d{8,11})(?!\d)""")

    // Fast path: every pattern above needs one of these triggers; plain text skips the regexes entirely.
    private val TRIGGER = Regex("""[@=\d]|eyj|bearer|basic|_live_|_test_|_sec_""", RegexOption.IGNORE_CASE)

    private fun luhn(digits: String): Boolean {
        var sum = 0
        for (i in digits.indices) {
            var d = digits[digits.length - 1 - i].code - 48
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** NIK: province 11-94, birth day 01-31 (female +40), month 01-12. */
    private fun looksLikeNik(d: String): Boolean {
        val prov = d.substring(0, 2).toInt()
        val day = d.substring(6, 8).toInt()
        val month = d.substring(8, 10).toInt()
        return prov in 11..94 && (day in 1..31 || day in 41..71) && month in 1..12
    }

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        return "${email.substring(0, 1)}***${email.substring(at)}"
    }

    fun redactString(s: String): String {
        if (!TRIGGER.containsMatchIn(s)) return s
        var out = s
        out = JWT_RE.replace(out, REDACTED)
        out = BEARER_RE.replace(out, "Bearer $REDACTED")
        out = BASIC_RE.replace(out, "Basic $REDACTED")
        for (re in API_KEY_RES) out = re.replace(out, REDACTED)
        out = UrlScrubber.redactQueryParams(out)
        out = EMAIL_RE.replace(out) { maskEmail(it.value) }
        out = CARD_RE.replace(out) { m ->
            val pre = m.groupValues[1]
            val digits = m.groupValues[2].replace(" ", "").replace("-", "")
            when {
                digits.length < 13 || digits.length > 19 -> m.value
                // 13-digit epoch millis (e.g. 1762331598050) is not a card; no card brand has 13 digits starting with 1.
                digits.length == 13 && digits.startsWith("1") -> m.value
                luhn(digits) -> "$pre**** **** **** ${digits.takeLast(4)}"
                else -> m.value
            }
        }
        out = NIK_RE.replace(out) { m -> if (looksLikeNik(m.groupValues[2])) "${m.groupValues[1]}$REDACTED" else m.value }
        out = PHONE_RE.replace(out) { m ->
            val num = m.groupValues[2]
            val plus = if (num.startsWith("+")) "+" else ""
            "${m.groupValues[1]}$plus${"*".repeat(num.length - plus.length - 4)}${num.takeLast(4)}"
        }
        return out
    }

    private val SHORT_WORDS = setOf("pass", "pwd", "pin", "otp", "cvv", "cvc", "ssn", "nik", "card")
    private val LONG_SUBSTRINGS = listOf(
        "password", "passwd", "secret", "token", "authorization", "cookie", "apikey", "creditcard", "cardnumber",
    )
    private val CAMEL_RE = Regex("([a-z0-9])([A-Z])")
    private val NON_ALNUM_RUN = Regex("[^a-z0-9]+")
    private val NON_ALNUM = Regex("[^a-z0-9]")

    /**
     * Short words match as whole words (className, shipping, passenger are not hit); long words as substrings of
     * the normalized key (userPassword, x-api-key, access_token).
     */
    private fun isSensitiveKey(key: String): Boolean {
        val words = key.replace(CAMEL_RE, "$1 $2").lowercase().split(NON_ALNUM_RUN)
        if (words.any { it in SHORT_WORDS }) return true
        val compact = key.lowercase().replace(NON_ALNUM, "")
        return LONG_SUBSTRINGS.any { compact.contains(it) }
    }

    private class Walk {
        var nodes = 0
        // Only ancestors count as circular; shared non-cyclic references are still processed.
        val seen: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    }

    /** Sentinel for values that are dropped (JS `undefined`: function etc.). */
    private object Skip

    private fun walk(v: Any?, depth: Int, ctx: Walk): Any? {
        when (v) {
            null -> return null
            is String -> return redactString(v)
            is Boolean -> return v
            is BigInteger, is BigDecimal -> return v.toString()
            is Number -> return v
            is Char -> return redactString(v.toString())
            is Enum<*> -> return v.name
            is Date -> return Iso8601.format(v.time)
            is Throwable -> return mapOf("name" to v.javaClass.simpleName, "message" to redactString(v.message ?: ""))
            is Function<*> -> return Skip
        }
        v as Any
        if (depth >= MAX_DEPTH) return MARKER_DEPTH
        if (v in ctx.seen) return MARKER_CIRCULAR
        if (++ctx.nodes > MAX_NODES) return MARKER_NODES
        ctx.seen.add(v)
        try {
            return when (v) {
                is Map<*, *> -> {
                    val out = LinkedHashMap<String, Any?>()
                    for ((k, value) in v) {
                        val key = k.toString()
                        if (isSensitiveKey(key)) {
                            out[key] = REDACTED
                        } else {
                            val w = walk(value, depth + 1, ctx)
                            if (w !== Skip) out[key] = w
                        }
                    }
                    out
                }
                is Iterable<*> -> mapSequence(v.iterator(), depth, ctx)
                is Array<*> -> mapSequence(v.iterator(), depth, ctx)
                is IntArray -> mapSequence(v.iterator(), depth, ctx)
                is LongArray -> mapSequence(v.iterator(), depth, ctx)
                is DoubleArray -> mapSequence(v.iterator(), depth, ctx)
                is FloatArray -> mapSequence(v.iterator(), depth, ctx)
                is ShortArray -> mapSequence(v.iterator(), depth, ctx)
                is ByteArray -> mapSequence(v.iterator(), depth, ctx)
                is BooleanArray -> mapSequence(v.iterator(), depth, ctx)
                is CharArray -> mapSequence(v.iterator(), depth, ctx)
                else -> redactString(v.toString())
            }
        } finally {
            ctx.seen.remove(v)
        }
    }

    private fun mapSequence(it: Iterator<*>, depth: Int, ctx: Walk): List<Any?> {
        val out = ArrayList<Any?>()
        while (it.hasNext()) {
            val w = walk(it.next(), depth + 1, ctx)
            out.add(if (w === Skip) null else w) // like JSON.stringify: undefined in an array becomes null
        }
        return out
    }

    private fun utf8Size(s: String): Int {
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

    /**
     * Redacts [attrs] (key deny-list, PII patterns in strings, depth/node limits) and enforces the [MAX_BYTES]
     * budget: top-level entries that do not fit are dropped and `_truncated = true` is added.
     */
    fun redact(attrs: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val walked = walk(attrs, 0, Walk()) as? Map<String, Any?> ?: return emptyMap()
        return enforceBudget(walked)
    }

    private fun enforceBudget(walked: Map<String, Any?>): Map<String, Any?> {
        if (utf8Size(Json.encode(walked)) <= MAX_BYTES) return walked
        val marker = ",\"$TRUNCATED_KEY\":true"
        var total = 2 + utf8Size(marker) // "{}" + marker
        val kept = LinkedHashMap<String, Any?>()
        for ((k, v) in walked) {
            if (k == TRUNCATED_KEY) continue
            val entry = utf8Size(Json.encode(k)) + 1 + utf8Size(Json.encode(v)) + 1 // key:value,
            if (total + entry > MAX_BYTES) continue
            total += entry
            kept[k] = v
        }
        kept[TRUNCATED_KEY] = true
        return kept
    }
}
