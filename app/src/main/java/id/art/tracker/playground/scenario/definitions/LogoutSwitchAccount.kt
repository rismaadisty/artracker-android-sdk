package id.art.tracker.playground.scenario.definitions

import id.art.tracker.playground.labs.Personas
import id.art.tracker.playground.scenario.Capability
import id.art.tracker.playground.scenario.Scenario
import id.art.tracker.playground.scenario.Step

internal val logoutSwitchAccount = Scenario(
    id = "logout_switch_account",
    title = "Logout dan ganti akun",
    description = "Identify persona pertama, reset() (sesi baru), lalu identify persona kedua. " +
        "Menunggu satu frame replay setelah reset karena sesi baru belum punya frame.",
    proves = setOf(Capability.IDENTITY, Capability.SESSION, Capability.EVENTS),
    steps = listOf(
        Step("Identify persona pertama") {
            val p = Personas.all[0]
            ScenarioSdk.impl.identify(p.userId, p.traits)
            ScenarioSdk.impl.track("login_succeeded")
        },
        Step("Aktivitas sebagai persona pertama") { ScenarioSdk.impl.track("profile_viewed") },
        Step("Logout (reset)") { ScenarioSdk.impl.reset() },
        Step("Tunggu frame sesi baru") { awaitReplayFrame() },
        Step("Identify persona kedua") {
            val p = Personas.all[1]
            ScenarioSdk.impl.identify(p.userId, p.traits)
            ScenarioSdk.impl.track("login_succeeded")
        },
    ),
)
