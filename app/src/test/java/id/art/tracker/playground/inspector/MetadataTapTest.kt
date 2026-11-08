package id.art.tracker.playground.inspector

import id.art.tracker.ItemType
import id.art.tracker.LogLevel
import id.art.tracker.TelemetryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTapTest {
    private fun item(
        name: String = "e",
        message: String? = null,
        userId: String? = null,
        attributes: Map<String, Any?> = emptyMap(),
        session: String = "0123456789abcdef",
    ) = TelemetryItem("id1", ItemType.EVENT, name, null, message, 1_000L, session, userId, attributes)

    @Test
    fun neverStoresAnyValue() {
        val out = MetadataTap.toInspected(
            item(
                message = "pwd hunter2",
                userId = "u-1",
                attributes = mapOf("password" to "hunter2", "email" to "qa@example.invalid", "note" to "hello"),
            ),
        )
        val text = out.toString()
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("qa@example.invalid"))
        assertFalse(text.contains("hello"))
        assertNull(out.message)
        assertNull(out.userId)
        assertEquals("string(7)", out.attributes["password"])
        assertEquals("string(5)", out.attributes["note"])
        assertEquals(ItemSource.METADATA_ONLY, out.source)
    }

    @Test
    fun valueKindsAreDescribed() {
        val out = MetadataTap.toInspected(
            item(attributes = mapOf("n" to 4, "d" to 1.5, "b" to true, "z" to null, "m" to mapOf("a" to 1), "l" to listOf(1, 2), "o" to Any())),
        )
        assertEquals("number", out.attributes["n"])
        assertEquals("number", out.attributes["d"])
        assertEquals("boolean", out.attributes["b"])
        assertEquals("null", out.attributes["z"])
        assertEquals("map", out.attributes["m"])
        assertEquals("list", out.attributes["l"])
        assertEquals("object", out.attributes["o"])
    }

    @Test
    fun unsafeNamesAreReplaced() {
        assertEquals("<nama disamarkan>", MetadataTap.toInspected(item(name = "qa@example.invalid")).name)
        assertEquals("<nama disamarkan>", MetadataTap.toInspected(item(name = "a".repeat(200))).name)
        listOf("checkout_clicked", "\$identify", "screen_view", "a.b:c/d-e").forEach {
            assertEquals(it, MetadataTap.toInspected(item(name = it)).name)
        }
    }

    @Test
    fun unsafeAttributeKeysAreReplacedWithoutCollisions() {
        val out = MetadataTap.toInspected(item(attributes = mapOf("a@b.c" to 1, "x y" to 2, "ok_key" to 3)))
        assertEquals(3, out.attributes.size)
        assertTrue(out.attributes.containsKey("ok_key"))
        assertEquals(2, out.attributes.keys.count { it.startsWith("<kunci disamarkan>") })
    }

    @Test
    fun sessionIdIsTruncatedToEightChars() {
        assertEquals("01234567", MetadataTap.toInspected(item()).sessionId)
    }

    @Test
    fun typeAndLevelAreLowercaseNames() {
        val log = TelemetryItem("i", ItemType.LOG, "http_request", LogLevel.WARN, null, 1L, "s", null, emptyMap())
        val out = MetadataTap.toInspected(log)
        assertEquals("log", out.type)
        assertEquals("warn", out.level)
    }

    @Test
    fun beforeSendReturnsTheSameInstanceAndNeverThrows() {
        val hostile = object {
            override fun toString(): String = throw IllegalStateException("boom")
        }
        val items = listOf(
            item(),
            item(attributes = mapOf("nan" to Double.NaN, "nested" to mapOf("a" to mapOf("b" to 1)), "lone" to "\uD800", "h" to hostile)),
        )
        for (i in items) assertSame(i, MetadataTap.beforeSend.process(i))
    }
}
