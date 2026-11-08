package id.art.tracker.telemetry

import id.art.tracker.testutil.FakeClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashItemBuilderTest {
    private fun build(
        t: Throwable,
        breadcrumbs: Breadcrumbs = Breadcrumbs(),
        userId: String? = "u-1",
        screen: String? = "Checkout",
    ): JSONObject {
        val (id, json) = CrashItemBuilder.build(
            thread = Thread.currentThread(),
            t = t,
            nowMs = 1_762_331_598_050,
            sessionId = "sess-1",
            userId = userId,
            appVersion = "1.2.3",
            screen = screen,
            breadcrumbs = breadcrumbs,
        )
        val o = JSONObject(json)
        assertEquals(id, o.getString("id"))
        return o
    }

    @Test
    fun itemHasTheFinalShape() {
        val b = Breadcrumbs(clock = FakeClock(1_762_331_598_000))
        b.add("screen", "Checkout", mapOf("previous" to "Cart"))
        val o = build(IllegalStateException("bad state", RuntimeException("root")), b)

        assertEquals("log", o.getString("type"))
        assertEquals("fatal", o.getString("level"))
        assertEquals("crash", o.getString("name"))
        assertEquals("java.lang.IllegalStateException: bad state", o.getString("message"))
        assertEquals("2025-11-05T08:33:18.050Z", o.getString("timestamp"))
        assertEquals("sess-1", o.getString("session_id"))
        assertEquals("u-1", o.getString("user_id"))
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(o.getString("id")))
        val a = o.getJSONObject("attributes")
        assertEquals(Thread.currentThread().name, a.getString("thread"))
        assertEquals("java.lang.IllegalStateException", a.getString("exception_class"))
        assertTrue(a.getString("stacktrace").contains("Caused by: java.lang.RuntimeException: root"))
        assertEquals(40, a.getString("fingerprint").length)
        assertFalse(a.getBoolean("handled"))
        assertEquals("Checkout", a.getString("screen"))
        assertEquals("1.2.3", a.getString("app_version"))
        assertTrue(a.getLong("mem_free_bytes") > 0)
        val crumbs = a.getJSONArray("breadcrumbs")
        assertEquals(1, crumbs.length())
        assertEquals("Checkout", crumbs.getJSONObject(0).getString("message"))
        assertEquals("Cart", crumbs.getJSONObject(0).getJSONObject("data").getString("previous"))
    }

    @Test
    fun missingContext_usesNeutralValues() {
        val (_, json) = CrashItemBuilder.build(
            Thread.currentThread(), RuntimeException(), 1_000, null, null, null, null, Breadcrumbs(),
        )
        val o = JSONObject(json)
        assertEquals("unknown", o.getString("session_id"))
        assertFalse(o.has("user_id"))
        assertEquals("java.lang.RuntimeException", o.getString("message"))
        assertEquals("unknown", o.getJSONObject("attributes").getString("app_version"))
        assertFalse(o.getJSONObject("attributes").has("screen"))
    }

    @Test
    fun messageStackAndBreadcrumbs_areRedacted() {
        val b = Breadcrumbs()
        b.add("log", "payment by jane.doe@example.com failed")
        val t = RuntimeException("bad token for jane.doe@example.com", IllegalStateException("also a@b.co"))

        val o = build(t, b)

        assertFalse(o.getString("message").contains("jane.doe@example.com"))
        assertTrue(o.getString("message").contains("j***@example.com"))
        val a = o.getJSONObject("attributes")
        assertFalse(a.getString("stacktrace").contains("jane.doe@example.com"))
        assertFalse(a.getString("stacktrace").contains("a@b.co"))
        assertFalse(a.getJSONArray("breadcrumbs").toString().contains("jane.doe@example.com"))
    }

    @Test
    fun attributes_stayWithin24KB_evenForHugeInput() {
        val b = Breadcrumbs()
        repeat(30) { b.add("log", "x".repeat(200), mapOf("k" to "y".repeat(200))) }
        val t = RuntimeException("m".repeat(3_000), RuntimeException("\n\t\"quoted\"".repeat(500)))
        t.stackTrace = Array(5_000) { StackTraceElement("com.example.\"Cls$it\"", "method\t$it", "F.kt", it) }

        val (_, json) = CrashItemBuilder.build(Thread.currentThread(), t, 1, "s", "u", "1", "S", b)

        val attrs = JSONObject(json).getJSONObject("attributes").toString()
        assertTrue("attributes ${attrs.toByteArray().size} bytes", attrs.toByteArray(Charsets.UTF_8).size <= 24 * 1024)
        assertTrue(JSONObject(json).getString("message").length <= 4096)
    }

    @Test
    fun breadcrumbs_areOldestFirst_newestLast() {
        val b = Breadcrumbs()
        repeat(30) { b.add("event", "e$it") }
        val crumbs = build(RuntimeException(), b).getJSONObject("attributes").getJSONArray("breadcrumbs")
        assertEquals("e29", crumbs.getJSONObject(crumbs.length() - 1).getString("message"))
    }
}
