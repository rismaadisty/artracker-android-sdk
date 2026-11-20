package id.art.tracker.appstart

import id.art.tracker.core.SdkInfo
import id.art.tracker.testutil.StrictJson
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePayloadTest {
    private fun buffer(): StackSampleBuffer = StackSampleBuffer().also {
        it.add(300, arrayOf(StackTraceElement("com.example.Main", "onCreate", "Main.kt", 12), StackTraceElement("android.app.Activity", "performCreate", null, -1)))
        it.add(310, arrayOf(StackTraceElement("com.example.Main", "onCreate", "Main.kt", 12), StackTraceElement("android.app.Activity", "performCreate", null, -1)))
    }

    private fun json(build: String? = "1203") = ProfilePayload.json(
        startId = "0190-start",
        intervalMs = 10,
        appPackages = listOf("com.example"),
        buffer = buffer(),
        appVersion = "1.2.3",
        build = build,
        environment = "production",
    )

    @Test
    fun payloadMatchesTheWireContract() {
        val text = json()
        assertNull(StrictJson.validate(text))
        val root = JSONObject(text)

        assertEquals("artracker-android", root.getJSONObject("sdk").getString("name"))
        assertEquals(SdkInfo.VERSION, root.getJSONObject("sdk").getString("version"))
        val context = root.getJSONObject("context")
        assertEquals("1.2.3", context.getString("app_version"))
        assertEquals("1203", context.getString("build"))
        assertEquals("production", context.getString("environment"))

        val profile = root.getJSONObject("profile")
        assertEquals(1, profile.getInt("v"))
        assertEquals("0190-start", profile.getString("start_id"))
        assertEquals("android", profile.getString("platform"))
        assertEquals(10, profile.getInt("interval_ms"))
        assertEquals("process_start", profile.getString("t0"))
        assertEquals("com.example", profile.getJSONArray("app_packages").getString(0))
        assertEquals(2, profile.getJSONArray("frames").length())
        assertEquals(1, profile.getJSONArray("stacks").length())
        val samples = profile.getJSONArray("samples")
        assertEquals(2, samples.length())
        assertEquals(300, samples.getJSONObject(0).getInt("t"))
        assertEquals(0, samples.getJSONObject(1).getInt("s"))
        // root first: performCreate, then onCreate
        val stack = profile.getJSONArray("stacks").getJSONArray(0)
        assertEquals("performCreate", profile.getJSONArray("frames").getJSONObject(stack.getInt(0)).getString("m"))
    }

    @Test
    fun buildIsOmittedWhenNull() {
        val context = JSONObject(json(build = null)).getJSONObject("context")
        assertFalse(context.has("build"))
    }

    @Test
    fun gzipRoundTrips() {
        val text = json()
        val bytes = ProfilePayload.gzip(text)

        assertTrue(bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
        val back = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().toString(Charsets.UTF_8)
        assertEquals(text, back)
    }

    @Test
    fun aFullBuffer_isValidJson() {
        val full = StackSampleBuffer()
        repeat(1000) { i -> full.add(i * 10L, Array(128) { j -> StackTraceElement("c.C$j", "m", "C.kt", i % 7) }) }
        val text = ProfilePayload.json("id", 10, listOf("com.example"), full, "1", null, "test")

        assertNull(StrictJson.validate(text))
        assertEquals(1000, JSONObject(text).getJSONObject("profile").getJSONArray("samples").length())
    }
}
