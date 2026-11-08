package id.art.tracker.playground.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScenarioOutcomeTest {
    @Test
    fun aFailureNamesTheStepAndTheReason() {
        assertEquals("Skenario gagal di langkah 3: target 'x' tidak ditemukan", ScenarioOutcome.message(RunState.Failed(2, "target 'x' tidak ditemukan")))
    }

    @Test
    fun doneWithoutWarningsIsPlain() {
        assertEquals("Skenario selesai", ScenarioOutcome.message(RunState.Done(emptyList())))
    }

    @Test
    fun doneWithWarningsCountsThemAndShowsTheFirst() {
        assertEquals(
            "Skenario selesai dengan 2 peringatan: replay tidak merekam",
            ScenarioOutcome.message(RunState.Done(listOf("replay tidak merekam", "lain"))),
        )
    }

    @Test
    fun nonTerminalStatesHaveNoMessage() {
        assertNull(ScenarioOutcome.message(RunState.Idle))
        assertNull(ScenarioOutcome.message(RunState.Running(0, 3, "a")))
        assertNull(ScenarioOutcome.message(RunState.Cancelled))
    }
}
