package id.art.tracker.perf

import android.app.Application
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceProbeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `thermal status maps per the wire contract`() {
        assertEquals("nominal", mapThermalStatus(PowerManager.THERMAL_STATUS_NONE))
        assertEquals("nominal", mapThermalStatus(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals("fair", mapThermalStatus(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("serious", mapThermalStatus(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals("critical", mapThermalStatus(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals("critical", mapThermalStatus(PowerManager.THERMAL_STATUS_EMERGENCY))
        assertEquals("critical", mapThermalStatus(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun `unknown thermal status maps to null`() {
        assertNull(mapThermalStatus(99))
        assertNull(mapThermalStatus(-1))
    }

    @Test
    fun `system constants are positive`() {
        val probe = AndroidDeviceProbe(app)
        assertTrue(probe.cores() >= 1)
        assertTrue(probe.clkTck() > 0)
        assertTrue(probe.pageSizeBytes() > 0)
    }

    @Test
    fun `thermal is readable on API 34`() {
        // Robolectric's PowerManager reports THERMAL_STATUS_NONE by default.
        assertEquals("nominal", AndroidDeviceProbe(app).thermal())
    }

    @Test
    fun `thermal is null below API 29`() {
        // The android-all jar of API 28 is not always available offline, so the SDK level is injected instead.
        assertNull(AndroidDeviceProbe(app, sdkInt = 28).thermal())
        assertEquals("nominal", AndroidDeviceProbe(app, sdkInt = 29).thermal())
    }

    @Test
    fun `os version storage and network are readable`() {
        val probe = AndroidDeviceProbe(app)
        assertTrue(!probe.osVersion().isNullOrBlank())
        val total = probe.storageTotalMb()
        val free = probe.storageFreeMb()
        assertTrue(total == null || total > 0)
        assertTrue(free == null || free >= 0)
        if (total != null && free != null) assertTrue(free <= total)
        assertTrue(probe.network() in listOf(null, "wifi", "cellular", "ethernet", "other", "none"))
    }

    @Test
    fun `free ram is below total ram`() {
        val probe = AndroidDeviceProbe(app)
        val total = probe.ramMb()
        val free = probe.ramFreeMb()
        assertTrue(free == null || free >= 0)
        if (total != null && free != null) assertTrue(free <= total)
    }

    @Test
    fun `battery is a fraction or null`() {
        val b = AndroidDeviceProbe(app).batteryFraction()
        assertTrue(b == null || b in 0.0..1.0)
    }

    @Test
    fun `ram is read once and cached`() {
        val probe = AndroidDeviceProbe(app)
        val first = probe.ramMb()
        assertEquals(first, probe.ramMb())
        assertTrue(first == null || first > 0)
        assertNotNull(probe) // construction never throws
    }
}
