package id.art.tracker.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceContextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sessionStartAttributes_hasAllKeys() {
        val attrs = DeviceContext(context, "1.2.3").sessionStartAttributes()
        val expected = setOf(
            "platform", "os_version", "api_level", "manufacturer", "model",
            "app_version", "app_build", "screen", "locale", "tz", "network",
            "is_emulator", "sdk_version",
        )
        assertEquals(expected, attrs.keys)
        assertEquals("android", attrs["platform"])
        assertEquals(SdkInfo.VERSION, attrs["sdk_version"])
        assertEquals("1.2.3", attrs["app_version"])
        assertEquals(34, attrs["api_level"])
        val screen = attrs["screen"] as Map<*, *>
        assertEquals(setOf("w", "h", "density"), screen.keys)
        assertTrue(attrs["network"] in setOf("wifi", "cellular", "ethernet", "none", "unknown"))
    }

    @Test
    fun envelopeContext_containsOnlyThreeKeys() {
        val ctx = DeviceContext(context, "9.9").envelopeContext("staging")
        assertEquals(setOf("environment", "app_version", "sdk_platform"), ctx.keys)
        assertEquals("staging", ctx["environment"])
        assertEquals("9.9", ctx["app_version"])
        assertEquals("android", ctx["sdk_platform"])
    }

    @Test
    fun appVersion_prefersConfig() {
        assertEquals("custom-7", DeviceContext(context, "custom-7").appVersion)
    }

    @Test
    fun appVersion_fallsBackToPackageOrUnknown() {
        val v = DeviceContext(context, null).appVersion
        assertTrue(v.isNotEmpty())
    }
}
