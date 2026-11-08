package id.art.tracker.playground.scenario

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun host(userPrompts: MutableList<String> = mutableListOf()) = ScenarioHost(
        scope = scope,
        contextFor = { _, gate -> GatedContext(gate, userPrompts) },
        runner = ScenarioRunner(pause = {}),
    )

    private class GatedContext(private val gate: suspend (String) -> Unit, private val prompts: MutableList<String>) : ScenarioContext {
        override suspend fun navigate(route: String) = Unit
        override suspend fun tap(targetId: String) = Unit
        override suspend fun type(fieldId: String, text: String) = Unit
        override suspend fun awaitUser(prompt: String) { prompts += prompt; gate(prompt) }
        override suspend fun awaitReplayFrame() = Unit
        override fun preflight(): List<String> = emptyList()
        override fun note(message: String) = Unit
    }

    private val waiting = Scenario("w", "W", "d", setOf(Capability.OFFLINE), listOf(
        Step("one") { note("x") },
        Step("ask") { awaitUser("Aktifkan mode pesawat") },
        Step("three") { note("y") },
    ))

    @Test
    fun theRunWaitsAtAwaitUserUntilTheUserContinues() {
        val h = host()
        h.start(waiting)
        waitUntil("the user gate") { h.awaiting != null }
        assertEquals("Aktifkan mode pesawat", h.awaiting)
        assertTrue(h.state is RunState.Running)
        h.continueFromUser()
        waitUntil("done") { h.state is RunState.Done }
        assertNull(h.awaiting)
    }

    @Test
    fun stoppingCancelsARunThatIsWaitingForTheUser() {
        val h = host()
        h.start(waiting)
        waitUntil("the user gate") { h.awaiting != null }
        h.stop()
        waitUntil("cancelled") { h.state == RunState.Cancelled }
        assertNull(h.awaiting)
    }

    @Test
    fun startingAgainReplacesTheRunningScenario() {
        val h = host()
        h.start(waiting)
        waitUntil("the user gate") { h.awaiting != null }
        h.start(Scenario("quick", "Q", "d", setOf(Capability.EVENTS), listOf(Step("a") { note("1") })))
        waitUntil("quick done") { h.state is RunState.Done }
        assertEquals("quick", h.active)
    }

    @Test
    fun aSupersededRunsLateCancelDoesNotOverwriteTheNewRunsState() {
        val h = host()
        h.start(waiting)
        waitUntil("the user gate") { h.awaiting != null }
        h.start(Scenario("quick", "Q", "d", setOf(Capability.EVENTS), listOf(Step("a") { note("1") })))
        waitUntil("quick done") { h.state is RunState.Done }
        Thread.sleep(150) // the first run's Cancelled callback arrives in this window
        assertTrue("state was ${h.state}", h.state is RunState.Done)
    }

    @Test
    fun theOutcomeIsReportedOnceEvenAcrossRecompositions() {
        val h = host()
        h.start(Scenario("quick", "Q", "d", setOf(Capability.EVENTS), listOf(Step("a") { note("1") })))
        waitUntil("done") { h.state is RunState.Done }
        assertTrue(h.takeOutcome() is RunState.Done)
        assertNull(h.takeOutcome())
        h.start(Scenario("again", "A", "d", setOf(Capability.EVENTS), listOf(Step("a") { note("1") })))
        waitUntil("done again") { h.state is RunState.Done }
        assertTrue("a new run reports its own outcome", h.takeOutcome() is RunState.Done)
    }

    @Test
    fun cancelledAndRunningRunsReportNoOutcome() {
        val h = host()
        h.start(waiting)
        waitUntil("the user gate") { h.awaiting != null }
        assertNull(h.takeOutcome())
        h.stop()
        waitUntil("cancelled") { h.state == RunState.Cancelled }
        assertNull(h.takeOutcome())
    }
}
