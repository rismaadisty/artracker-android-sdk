package id.art.tracker.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlScrubberTest {
    private fun s(url: String) = UrlScrubber.scrub(url)
    private fun ids(url: String) = UrlScrubber.scrub(url, maskPathIds = true)

    @Test
    fun redactsSensitiveQueryParamsAndKeepsOthers() {
        assertEquals("https://a/x?token=[redacted]&page=2", s("https://a/x?token=abc&page=2"))
        assertEquals(
            "https://a/x?page=2&email=[redacted]&sig=[redacted]",
            s("https://a/x?page=2&email=budi@example.com&sig=zz"),
        )
    }

    @Test
    fun tokenParam() {
        assertEquals("https://a.com/p?token=[redacted]&q=1", s("https://a.com/p?token=abc&q=1"))
    }

    @Test
    fun paramNameIsCaseInsensitive() {
        assertEquals("/x?Access_Token=[redacted]", s("/x?Access_Token=abc"))
    }

    @Test
    fun fragmentWithSensitiveParamRedacted_routeHashKept() {
        assertEquals("https://a/cb#access_token=[redacted]&state=1", s("https://a/cb#access_token=abc&state=1"))
        assertEquals("https://a/#/dashboard", s("https://a/#/dashboard"))
    }

    @Test
    fun urlWithoutSensitiveDataUnchanged() {
        assertEquals(
            "https://a.example:8080/api/v1/items?limit=50",
            s("https://a.example:8080/api/v1/items?limit=50"),
        )
    }

    @Test
    fun maskPathIds_replacesUuidAndLongNumbers() {
        assertEquals(
            "https://a/users/:id/orders/:id?x=1",
            ids("https://a/users/12345/orders/3fa85f64-5717-4562-b3fc-2c963f66afa6?x=1"),
        )
    }

    @Test
    fun maskPathIds_leavesHostPortAndWordSegments() {
        assertEquals("http://127.0.0.1:8080/v1/items", ids("http://127.0.0.1:8080/v1/items"))
    }

    @Test
    fun maskPathIds_doesNotTouchQueryOrFragment() {
        assertEquals("https://a/u/:id?ref=/p/123456", ids("https://a/u/123456?ref=/p/123456"))
    }

    @Test
    fun maskPathIds_trailingNewlineIsNotEndOfInput() {
        // JS `$` (no m flag) matches only at the very end; Java's would also match before a final \n.
        assertEquals("/u/12345\nx", ids("/u/12345\nx"))
    }

    @Test
    fun nonUrlInputDoesNotThrow() {
        assertEquals("", s(""))
        assertEquals("::::", s("::::"))
    }

    @Test
    fun redactQueryParams_worksOnArbitraryText() {
        assertEquals("GET /cb?code=[redacted]&page=2", UrlScrubber.redactQueryParams("GET /cb?code=abc123&page=2"))
        // value stops at quote/backslash so it is safe on JSON text
        assertEquals("{\"u\":\"/x?token=[redacted]\"}", UrlScrubber.redactQueryParams("{\"u\":\"/x?token=abc\"}"))
    }

    @Test
    fun sensitiveParamHint() {
        assertEquals(true, UrlScrubber.SENSITIVE_PARAM_HINT.containsMatchIn("/x?TOKEN=1"))
        assertEquals(false, UrlScrubber.SENSITIVE_PARAM_HINT.containsMatchIn("/x?page=1"))
    }
}
