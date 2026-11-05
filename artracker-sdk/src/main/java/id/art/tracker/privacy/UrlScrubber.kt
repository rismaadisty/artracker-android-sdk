package id.art.tracker.privacy

import java.util.regex.Pattern

/** Port of the web SDK `urlScrubber.ts`; patterns are kept identical (JS semantics noted inline). */
internal object UrlScrubber {
    private const val REDACTED = "[redacted]"

    /** JS `\s` (ECMAScript WhiteSpace + LineTerminator); Java's `\s` is ASCII-only. */
    internal const val JS_WS =
        "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    private val SENSITIVE_PARAMS = listOf(
        "token", "access_token", "id_token", "refresh_token", "code", "email",
        "sig", "signature", "api_key", "apikey", "password", "secret",
    ).joinToString("|")

    // The value stops at a quote/backslash so it is safe to run over JSON text (replay chunker).
    private val PARAM_RE = Regex("""([?&#;])($SENSITIVE_PARAMS)=([^&#$JS_WS"\\]*)""", RegexOption.IGNORE_CASE)

    /** Quick check: does the string possibly contain a sensitive param? */
    val SENSITIVE_PARAM_HINT: Regex = Regex("""[?&#;]($SENSITIVE_PARAMS)=""", RegexOption.IGNORE_CASE)

    // `\z` instead of `$`: JS `$` (no m flag) only matches at the very end, Java's `$` also before a final \n.
    private val PATH_ID_RE = Regex(
        """/(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\d{4,})(?=/|\z)""",
        RegexOption.IGNORE_CASE,
    )

    /** Redacts the value of sensitive query/fragment params inside any string. */
    fun redactQueryParams(input: String): String =
        PARAM_RE.replace(input) { m -> "${m.groupValues[1]}${m.groupValues[2]}=$REDACTED" }

    fun scrub(url: String, maskPathIds: Boolean = false): String {
        if (url.isEmpty()) return ""
        var out = redactQueryParams(url)
        if (maskPathIds) {
            val cut = out.indexOfFirst { it == '?' || it == '#' }
            val pathPart = if (cut == -1) out else out.substring(0, cut)
            val rest = if (cut == -1) "" else out.substring(cut)
            out = PATH_ID_RE.replace(pathPart, "/:id") + rest
        }
        return out
    }
}
