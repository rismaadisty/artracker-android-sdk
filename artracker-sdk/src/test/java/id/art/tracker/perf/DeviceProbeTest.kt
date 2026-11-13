package id.art.tracker.perf

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Environment
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs

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
    fun `os version and storage are readable`() {
        val probe = AndroidDeviceProbe(app)
        assertTrue(!probe.osVersion().isNullOrBlank())
        val total = probe.storageTotalMb()
        val free = probe.storageFreeMb()
        assertTrue(total == null || total > 0)
        assertTrue(free == null || free >= 0)
        if (total != null && free != null) assertTrue(free <= total)
    }

    @Test
    fun `free ram is below total ram`() {
        val probe = AndroidDeviceProbe(app)
        val total = probe.ramMb()
        val free = probe.memory()?.freeMb
        assertTrue(free == null || free >= 0)
        if (total != null && free != null) assertTrue(free <= total)
    }

    @Test
    fun `ram free is availMem in MB`() {
        val info = ActivityManager.MemoryInfo().apply {
            totalMem = 8_192L * 1_048_576
            availMem = 2_310L * 1_048_576
        }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setMemoryInfo(info)
        assertEquals(MemoryReading(2310, false), AndroidDeviceProbe(app).memory())
    }

    @Test
    fun `memory reading carries the low memory flag of the system`() {
        val info = ActivityManager.MemoryInfo().apply {
            totalMem = 8_192L * 1_048_576
            availMem = 120L * 1_048_576
            lowMemory = true
        }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setMemoryInfo(info)
        assertEquals(MemoryReading(120, true), AndroidDeviceProbe(app).memory())
    }

    @Test
    fun `storage reads the data volume`() {
        ShadowStatFs.registerStats(Environment.getDataDirectory().path, 31_250_000, 25_000_000, 25_000_000)
        val probe = AndroidDeviceProbe(app)
        assertEquals(97_656, probe.storageFreeMb())
        assertEquals(122_070, probe.storageTotalMb())
    }

    @Test
    fun `storage failure omits storage fields only`() {
        val probe = AndroidDeviceProbe(app, statFs = { throw IllegalArgumentException("no such volume") })
        assertNull(probe.storageFreeMb())
        assertNull(probe.storageTotalMb())
        assertNotNull(probe.memory())
    }

    @Test
    fun `a failed storage read is retried and not cached`() {
        var calls = 0
        val probe = AndroidDeviceProbe(app, statFs = {
            if (calls++ == 0) throw IllegalArgumentException("transient") else android.os.StatFs(it)
        })
        ShadowStatFs.registerStats(Environment.getDataDirectory().path, 31_250_000, 25_000_000, 25_000_000)
        assertNull(probe.storageTotalMb())
        assertEquals(122_070, probe.storageTotalMb())
        assertEquals(122_070, probe.storageTotalMb())
        assertEquals(2, calls)
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
