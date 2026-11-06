package id.art.tracker.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayHrefTest {
    @Test
    fun plainNames_areUnchanged() {
        assertEquals("android://CheckoutActivity", ReplayHref.of("CheckoutActivity"))
        assertEquals("android://settings.profile_v2-x", ReplayHref.of("settings.profile_v2-x"))
        assertEquals("android://product/detail", ReplayHref.of("product/detail"))
    }

    @Test
    fun email_isRedacted_andUrlSafe() {
        val href = ReplayHref.of("Profile john@doe.com")
        assertFalse(href.contains("john"))
        assertFalse(href.contains("@"))
        assertFalse(href.contains(" "))
        assertTrue(href, href.removePrefix("android://").all { it.isLetterOrDigit() || it in "._-/:" })
    }

    @Test
    fun jwt_isRedacted() {
        val href = ReplayHref.of("Detail eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.c2lnbmF0dXJlMTIz")
        assertFalse(href.contains("eyJ"))
        assertFalse(href.contains("c2lnbmF0dXJlMTIz"))
        assertTrue(href.startsWith("android://Detail_"))
    }

    @Test
    fun pathIds_areMasked() {
        assertEquals("android://order/:id/items", ReplayHref.of("order/123456/items"))
        assertEquals("android://user/:id", ReplayHref.of("user/0197a5b0-7c3e-7a4b-9d2f-1e8f6c5a4b3d"))
    }

    @Test
    fun brackets_andSpaces_areReplaced_andResultIsStable() {
        val a = ReplayHref.of("My [screen] name")
        assertEquals("android://My__screen__name", a)
        assertEquals(a, ReplayHref.of("My [screen] name"))
    }

    @Test
    fun noAlphanumericLeft_becomesUnknown() {
        assertEquals("android://unknown", ReplayHref.of("首页"))
        assertEquals("android://unknown", ReplayHref.of("设置"))
        assertEquals("android://unknown", ReplayHref.of("[] / :"))
        assertEquals("android://unknown", ReplayHref.of(null))
        assertEquals("android://_a", ReplayHref.of("首a"))
    }

    @Test
    fun blankOrHuge_isBounded() {
        assertEquals("android://unknown", ReplayHref.of(" "))
        assertTrue(ReplayHref.of("a".repeat(5_000)).length <= "android://".length + 200)
    }
}
