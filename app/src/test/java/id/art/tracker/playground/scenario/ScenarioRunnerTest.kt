package id.art.tracker.playground.scenario

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FakeContext(private val warnings: List<String> = emptyList()) : ScenarioContext {
    val calls = mutableListOf<String>()
    override suspend fun navigate(route: String) { calls += "navigate:$route" }
    override suspend fun tap(targetId: String) { calls += "tap:$targetId" }
    override suspend fun type(fieldId: String, text: String) { calls += "type:$fieldId" }
    override suspend fun awaitUser(prompt: String) { calls += "awaitUser" }
    override suspend fun awaitReplayFrame() { calls += "awaitReplayFrame" }
    override fun preflight(): List<String> { calls += "preflight"; return warnings }
    override fun note(message: String) { calls += "note" }
}

class ScenarioRunnerTest {
    private fun scenario(vararg steps: Step) = Scenario("s", "S", "d", setOf(Capability.EVENTS), steps.toList())

    @Test
    fun runsEveryStepInOrderThenDoneWithPreflightWarnings() = runBlocking {
        val ctx = FakeContext(warnings = listOf("w1"))
        val states = mutableListOf<RunState>()
        val s = scenario(
            Step("a") { navigate("x") },
            Step("b") { tap("t") },
            Step("c") { note("n") },
        )
        ScenarioRunner(pause = {}).run(s, ctx, states::add)
        assertEquals(
            listOf(RunState.Running(0, 3, "a"), RunState.Running(1, 3, "b"), RunState.Running(2, 3, "c"), RunState.Done(listOf("w1"))),
            states,
        )
        assertEquals(listOf("preflight", "navigate:x", "tap:t", "note"), ctx.calls)
    }

    @Test
    fun preflightIsCalledExactlyOnceBeforeTheFirstStep() = runBlocking {
        val ctx = FakeContext()
        ScenarioRunner(pause = {}).run(scenario(Step("a") { note("1") }, Step("b") { note("2") }), ctx) { }
        assertEquals(1, ctx.calls.count { it == "preflight" })
        assertEquals("preflight", ctx.calls.first())
    }

    @Test
    fun aFailingStepStopsTheRunAndReportsItsIndex() = runBlocking {
        val ctx = FakeContext()
        val states = mutableListOf<RunState>()
        val s = scenario(
            Step("ok") { note("1") },
            Step("boom") { throw IllegalStateException("target 'x' tidak ditemukan") },
            Step("never") { note("3") },
        )
        ScenarioRunner(pause = {}).run(s, ctx, states::add)
        assertEquals(RunState.Failed(1, "target 'x' tidak ditemukan"), states.last())
        assertEquals(2, states.count { it is RunState.Running })
        assertTrue(ctx.calls.none { it == "note" && ctx.calls.indexOf(it) > 2 })
    }

    @Test
    fun cancellationReportsCancelledAndIsNotSwallowed() = runBlocking {
        val states = mutableListOf<RunState>()
        var cancelled: Throwable? = null
        val s = scenario(Step("hang") { awaitCancellation() })
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ScenarioRunner(pause = {}).run(s, FakeContext(), states::add)
            } catch (e: CancellationException) {
                cancelled = e
                throw e
            }
        }
        job.cancelAndJoin()
        assertEquals(RunState.Cancelled, states.last())
        assertTrue(cancelled is CancellationException)
    }

    @Test
    fun pauseAfterMsIsPassedToThePauseHookInOrder() = runBlocking {
        val pauses = mutableListOf<Long>()
        val s = scenario(
            Step("a", pauseAfterMs = 100) { note("1") },
            Step("b", pauseAfterMs = 200) { note("2") },
            Step("c", pauseAfterMs = 300) { note("3") },
        )
        ScenarioRunner(pause = { pauses += it }).run(s, FakeContext()) { }
        assertEquals(listOf(100L, 200L, 300L), pauses)
    }
}
