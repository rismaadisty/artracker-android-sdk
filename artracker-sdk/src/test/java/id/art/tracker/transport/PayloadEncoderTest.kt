package id.art.tracker.transport

import id.art.tracker.core.Iso8601
import id.art.tracker.core.SdkInfo
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadEncoderTest {
    private val sentAtMs = 1_700_000_000_123L

    private val encoder = PayloadEncoder {
        mapOf("environment" to "test", "app_version" to "1.2.3", "sdk_platform" to "android")
    }

    private fun items() = (0 until 3).map {
        StoredItem(
            id = "id-$it",
            priority = 2,
            createdAtMs = sentAtMs + it,
            payloadJson = "{\"id\":\"id-$it\",\"type\":\"event\",\"name\":\"né$it\"}",
        )
    }

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }

    @Test
    fun gzip_hasMagicBytes() {
        val bytes = encoder.encodeGzip(items(), sentAtMs)
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
    }

    @Test
    fun roundTrip() {
        val input = items()
        val json = JSONObject(gunzip(encoder.encodeGzip(input, sentAtMs)))
        assertEquals(Iso8601.format(sentAtMs), json.getString("sent_at"))
        assertEquals("artracker-android", json.getJSONObject("sdk").getString("name"))
        assertEquals(SdkInfo.VERSION, json.getJSONObject("sdk").getString("version"))
        assertEquals("android", json.getJSONObject("context").getString("sdk_platform"))
        assertEquals(3, json.getJSONArray("items").length())
        assertEquals(input[0].id, json.getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals("né2", json.getJSONArray("items").getJSONObject(2).getString("name"))
    }

    @Test
    fun gzipAndPlain_produceSameJson() {
        val input = items()
        assertEquals(encoder.envelopeJson(input, sentAtMs), gunzip(encoder.encodeGzip(input, sentAtMs)))
    }

    @Test
    fun emptyItems_yieldsEmptyArray() {
        val json = JSONObject(encoder.envelopeJson(emptyList(), sentAtMs))
        assertEquals(0, json.getJSONArray("items").length())
        assertTrue(json.has("context"))
    }
}
