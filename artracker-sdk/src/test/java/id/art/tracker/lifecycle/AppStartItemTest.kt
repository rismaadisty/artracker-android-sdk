package id.art.tracker.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppStartItemTest {
    private val start = AppStart(
        type = StartType.COLD,
        durationMs = 2_412,
        firstScreen = "MainActivity",
        phases = listOf(Phase("process_to_init", 310), Phase("application_on_create", 1_502), Phase("activity_create", 380), Phase("first_draw", 220)),
        trampoline = false,
        anchorUptimeMs = 1_000,
    )

    @Test
    fun attributesFollowTheWireContract() {
        val attrs = appStartAttributes(
            start = start,
            startId = "0190-start",
            profiled = false,
            build = "1203",
            device = AppStartDevice(model = "Pixel 8", osVersion = "14", ramMb = 7_800, cores = 9),
        )

        assertEquals(
            mapOf(
                "v" to 1,
                "platform" to "android",
                "start_id" to "0190-start",
                "type" to "cold",
                "duration_ms" to 2_412L,
                "phases" to listOf(
                    mapOf("name" to "process_to_init", "ms" to 310L),
                    mapOf("name" to "application_on_create", "ms" to 1_502L),
                    mapOf("name" to "activity_create", "ms" to 380L),
                    mapOf("name" to "first_draw", "ms" to 220L),
                ),
                "first_screen" to "MainActivity",
                "trampoline" to false,
                "profiled" to false,
                "build" to "1203",
                "device" to mapOf("model" to "Pixel 8", "os_version" to "14", "ram_mb" to 7_800, "cores" to 9),
            ),
            attrs,
        )
    }

    @Test
    fun profiledAndTrampolineAndTypeAreCarried() {
        val attrs = appStartAttributes(
            start.copy(type = StartType.WARM, trampoline = true),
            "id",
            profiled = true,
            build = null,
            device = AppStartDevice(null, null, null, null),
        )
        assertEquals("warm", attrs["type"])
        assertEquals(true, attrs["trampoline"])
        assertEquals(true, attrs["profiled"])
    }

    @Test
    fun nullDeviceFieldsAndBuildAreOmitted() {
        val attrs = appStartAttributes(start, "id", false, build = null, device = AppStartDevice("Pixel 8", "14", ramMb = null, cores = 8))

        assertFalse(attrs.containsKey("build"))
        assertEquals(mapOf("model" to "Pixel 8", "os_version" to "14", "cores" to 8), attrs["device"])
    }

    @Test
    fun anEmptyDeviceIsOmitted() {
        val attrs = appStartAttributes(start, "id", false, build = null, device = AppStartDevice(null, null, null, null))
        assertFalse(attrs.containsKey("device"))
    }
}
