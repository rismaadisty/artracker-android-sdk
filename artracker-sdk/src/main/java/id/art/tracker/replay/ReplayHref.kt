package id.art.tracker.replay

import id.art.tracker.privacy.AttributeRedactor
import id.art.tracker.privacy.UrlScrubber

/**
 * Builds the rrweb Meta `href` (`android://<screen>`) from a screen name. Screen names are host-supplied
 * (`ARTracker.screen`, navigation routes/labels, `@ARTrackerScreenName`) and replay chunks skip the layer-2
 * attribute redactor, so the name is sanitized here, in this order:
 *
 * 1. [AttributeRedactor.redactString]: emails, JWTs, tokens, card/phone/NIK numbers (as in `screen_view`);
 * 2. path ids masked (`/123456` or `/<uuid>` -> `/:id`, [UrlScrubber.scrub] with `maskPathIds`): names are often
 *    navigation routes, and a resolved route id identifies a record/user just like a URL path id does;
 * 3. every character outside `[A-Za-z0-9._-/:]` replaced with `_`: always a valid URL path, readable in the
 *    player UI (percent-encoding would show `%20`/`%5B`), and it neutralises the `[redacted]` markers' brackets;
 * 4. capped at [MAX_LENGTH] characters; a result without any letter or digit (blank, or a non-Latin name such as
 *    `首页` that became only `_`) -> `unknown`.
 *
 * Pure and deterministic, but LOSSY: distinct screens can share an href, so screen changes must be detected on the
 * raw name (the replay controller does), never on this value.
 */
internal object ReplayHref {
    private const val PREFIX = "android://"
    private const val MAX_LENGTH = 200
    private const val UNKNOWN = "unknown"

    fun of(screen: String?): String {
        if (screen.isNullOrBlank()) return PREFIX + UNKNOWN
        val redacted = AttributeRedactor.redactString(screen)
        // PATH_ID_RE needs a leading '/' to match the first segment as well.
        val masked = UrlScrubber.scrub("/$redacted", maskPathIds = true).removePrefix("/")
        val safe = buildString(masked.length) {
            for (c in masked) append(if (isAllowed(c)) c else '_')
        }.take(MAX_LENGTH)
        return PREFIX + if (safe.any { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) safe else UNKNOWN
    }

    private fun isAllowed(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-' || c == '/' || c == ':'
}
