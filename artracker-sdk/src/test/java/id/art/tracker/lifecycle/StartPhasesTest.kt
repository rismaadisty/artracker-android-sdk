package id.art.tracker.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test

class StartPhasesTest {
    private fun names(phases: List<Phase>) = phases.map { it.name }

    @Test
    fun coldPhases_areContiguousAndSumToTheDuration() {
        val phases = coldPhases(processStartMs = 1000, initMs = 1310, firstCreateMs = 2812, resumedMs = 3192, frameMs = 3412)
        assertEquals(
            listOf(
                Phase("process_to_init", 310),
                Phase("application_on_create", 1502),
                Phase("activity_create", 380),
                Phase("first_draw", 220),
            ),
            phases,
        )
        assertEquals(2412L, phases.sumOf { it.ms })
    }

    @Test
    fun outOfOrderBoundaries_areClamped_sumUnchanged() {
        val phases = coldPhases(processStartMs = 1000, initMs = 1500, firstCreateMs = 1400, resumedMs = 1800, frameMs = 2000)
        assertEquals(0L, phases.first { it.name == "application_on_create" }.ms)
        assertEquals(1000L, phases.sumOf { it.ms })
        assertEquals(true, phases.all { it.ms >= 0 })
    }

    @Test
    fun warmAndHotPhaseNames() {
        val warm = warmPhases(createMs = 100, resumedMs = 400, frameMs = 550)
        assertEquals(listOf("activity_create", "first_draw"), names(warm))
        assertEquals(listOf(300L, 150L), warm.map { it.ms })
        val hot = hotPhases(startMs = 100, resumedMs = 180, frameMs = 240)
        assertEquals(listOf("activity_start", "first_draw"), names(hot))
        assertEquals(listOf(80L, 60L), hot.map { it.ms })
    }

    @Test
    fun splitOnApi35_replacesProcessToInit() {
        val cold = listOf(Phase("process_to_init", 310), Phase("application_on_create", 1502), Phase("activity_create", 380), Phase("first_draw", 220))
        val split = splitProcessToInit(cold, StartupTimestamps(forkMs = 0, bindApplicationMs = 120, applicationOnCreateMs = 250))
        assertEquals(
            listOf(
                Phase("bind_application", 120),
                Phase("content_providers", 130),
                Phase("app_on_create_pre_init", 60),
                Phase("application_on_create", 1502),
                Phase("activity_create", 380),
                Phase("first_draw", 220),
            ),
            split,
        )
        assertEquals(cold.sumOf { it.ms }, split.sumOf { it.ms })
    }

    @Test
    fun splitKeepsPhases_whenABoundaryIsMissingOrInconsistent() {
        val cold = listOf(Phase("process_to_init", 310), Phase("first_draw", 220))
        assertEquals(cold, splitProcessToInit(cold, StartupTimestamps(0, null, 250)))
        assertEquals(cold, splitProcessToInit(cold, StartupTimestamps(0, 120, null)))
        // bind 200 + cp 150 = 350 > 310
        assertEquals(cold, splitProcessToInit(cold, StartupTimestamps(0, 200, 350)))
        // bind negative
        assertEquals(cold, splitProcessToInit(cold, StartupTimestamps(100, 50, 250)))
        // cp negative
        assertEquals(cold, splitProcessToInit(cold, StartupTimestamps(0, 120, 100)))
    }

    @Test
    fun splitWithoutProcessToInit_returnsInput() {
        val warm = listOf(Phase("activity_create", 10), Phase("first_draw", 5))
        assertEquals(warm, splitProcessToInit(warm, StartupTimestamps(0, 1, 2)))
    }
}
