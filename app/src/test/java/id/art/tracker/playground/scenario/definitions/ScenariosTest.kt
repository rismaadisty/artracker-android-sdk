package id.art.tracker.playground.scenario.definitions

import id.art.tracker.playground.scenario.Capability
import id.art.tracker.playground.scenario.RunState
import id.art.tracker.playground.scenario.Scenario
import id.art.tracker.playground.scenario.ScenarioContext
import id.art.tracker.playground.scenario.ScenarioRunner
import id.art.tracker.playground.scenario.shop.ShopTargets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RecordingContext(val log: MutableList<String>) : ScenarioContext {
    override suspend fun navigate(route: String) { log += "navigate:$route" }
    override suspend fun tap(targetId: String) { log += "tap:$targetId" }
    override suspend fun type(fieldId: String, text: String) { log += "type:$fieldId" }
    override suspend fun awaitUser(prompt: String) { log += "awaitUser" }
    override suspend fun awaitReplayFrame() { log += "awaitReplayFrame" }
    override fun preflight(): List<String> = emptyList()
    override fun note(message: String) { log += "note" }
}

internal class RecordingSdk(private val log: MutableList<String>) : SdkCalls {
    override fun track(name: String, properties: Map<String, Any?>) { log += "sdk:track:$name" }
    override fun screen(name: String) { log += "sdk:screen:$name" }
    override fun identify(userId: String, traits: Map<String, Any?>) { log += "sdk:identify:$userId" }
    override fun reset() { log += "sdk:reset" }
    override fun captureException(throwable: Throwable) { log += "sdk:captureException" }
    override fun flush() { log += "sdk:flush" }
    override suspend fun httpGet(path: String): String? { log += "sdk:http:$path"; return "HTTP 404" }
}

class ScenariosTest {
    @After
    fun restoreTheRealSdkCalls() {
        ScenarioSdk.impl = RealSdkCalls
    }

    private fun scenario(id: String): Scenario = Scenarios.all.first { it.id == id }

    /** Runs the scenario against recording fakes; returns the shared call log and the final run state. */
    private fun run(s: Scenario): Pair<List<String>, RunState> = runBlocking {
        val log = mutableListOf<String>()
        ScenarioSdk.impl = RecordingSdk(log)
        var last: RunState = RunState.Idle
        ScenarioRunner(pause = {}).run(s, RecordingContext(log)) { last = it }
        log to last
    }

    @Test
    fun theSixScenariosExistWithUniqueIds() {
        assertEquals(
            listOf("ecommerce_checkout", "onboarding_form", "frustrated_taps", "logout_switch_account", "offline_user", "long_session"),
            Scenarios.all.map { it.id },
        )
    }

    @Test
    fun everyScenarioIsWellFormed() {
        Scenarios.all.forEach {
            assertTrue(it.id, it.steps.size >= 3)
            assertTrue(it.id, it.steps.all { s -> s.label.isNotBlank() })
            assertTrue(it.id, it.proves.isNotEmpty())
            assertTrue(it.id, it.title.isNotBlank() && it.description.isNotBlank())
        }
    }

    @Test
    fun theScenariosTogetherCoverTheCoreCapabilities() {
        val covered = Scenarios.all.flatMap { it.proves }.toSet()
        val needed = setOf(
            Capability.EVENTS, Capability.IDENTITY, Capability.SCREENS, Capability.ERRORS, Capability.HTTP,
            Capability.REPLAY, Capability.MASKING, Capability.OFFLINE, Capability.SESSION,
        )
        assertTrue("missing ${needed - covered}", covered.containsAll(needed))
    }

    @Test
    fun everyScenarioRunsToDoneAgainstFakes() {
        Scenarios.all.forEach {
            val (_, state) = run(it)
            assertTrue("${it.id} ended in $state", state is RunState.Done)
        }
    }

    @Test
    fun offlineUserWaitsForTheUserAtLeastTwice() {
        assertTrue(run(scenario("offline_user")).first.count { it == "awaitUser" } >= 2)
    }

    @Test
    fun everyScenarioThatTapsWaitsForAReplayFrameBeforeItsFirstTap() {
        for (id in listOf("ecommerce_checkout", "onboarding_form", "frustrated_taps")) {
            val log = run(scenario(id)).first
            val firstTap = log.indexOfFirst { it.startsWith("tap:") }
            val frame = log.indexOf("awaitReplayFrame")
            assertTrue("$id has no tap", firstTap >= 0)
            assertTrue("$id: awaitReplayFrame ($frame) must come before the first tap ($firstTap)", frame in 0 until firstTap)
        }
    }

    @Test
    fun logoutWaitsForAReplayFrameRightAfterReset() {
        val log = run(scenario("logout_switch_account")).first
        val reset = log.indexOf("sdk:reset")
        assertTrue(reset >= 0)
        assertEquals("awaitReplayFrame", log[reset + 1])
    }

    @Test
    fun noScenarioCrashesTheAppOrGeneratesLoad() {
        Scenarios.all.forEach {
            val log = run(it).first
            assertTrue(it.id, log.none { call -> call.contains("crash", ignoreCase = true) || call.contains("anr", ignoreCase = true) })
            assertTrue(it.id, log.count { call -> call.startsWith("sdk:track") } <= 20)
        }
    }

    @Test
    fun theHttpStepHitsTheLocal404Target() {
        assertTrue(run(scenario("ecommerce_checkout")).first.contains("sdk:http:/status/404"))
    }

    @Test
    fun everyTapTargetAScenarioUsesIsOneTheShopScreensDeclare() {
        val used = Scenarios.all.flatMap { run(it).first }.filter { it.startsWith("tap:") }.map { it.removePrefix("tap:") }.toSet()
        assertTrue("undeclared targets: ${used - ShopTargets.ALL}", ShopTargets.ALL.containsAll(used))
    }

    @Test
    fun frustratedTapsSaysTheSdkFlagsTheChunkAsARageClick() {
        val description = scenario("frustrated_taps").description
        assertTrue(description.contains("has_rage_click"))
        assertFalse("the SDK detects rage taps now", description.contains("tidak mendeteksi"))
    }

    @Test
    fun logoutAndOnboardingIdentifyDifferentUsers() {
        val ids = run(scenario("logout_switch_account")).first.filter { it.startsWith("sdk:identify:") }.toSet()
        assertEquals(2, ids.size)
    }
}
