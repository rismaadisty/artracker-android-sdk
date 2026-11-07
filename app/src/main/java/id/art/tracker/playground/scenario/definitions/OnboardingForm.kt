package id.art.tracker.playground.scenario.definitions

import id.art.tracker.playground.scenario.Capability
import id.art.tracker.playground.scenario.Scenario
import id.art.tracker.playground.scenario.Step
import id.art.tracker.playground.scenario.shop.ShopFields
import id.art.tracker.playground.scenario.shop.ShopRoutes
import id.art.tracker.playground.scenario.shop.ShopTargets

internal val onboardingForm = Scenario(
    id = "onboarding_form",
    title = "Pendaftaran bertahap",
    description = "Formulir tiga langkah (nama, email, password) lalu identify user baru. Menguji masking input dan identitas.",
    proves = setOf(Capability.IDENTITY, Capability.EVENTS, Capability.SCREENS, Capability.MASKING, Capability.REPLAY),
    steps = listOf(
        Step("Buka formulir") { navigate(ShopRoutes.ONBOARDING) },
        Step("Tunggu snapshot replay") { awaitReplayFrame() },
        Step("Ketik nama") { type(ShopFields.ONB_NAME, "Fake User") },
        Step("Langkah 1 selesai") { tap(ShopTargets.ONB_NEXT_1) },
        Step("Ketik email") { type(ShopFields.ONB_EMAIL, "fake.user@example.invalid") },
        Step("Langkah 2 selesai") { tap(ShopTargets.ONB_NEXT_2) },
        Step("Ketik password (selalu di-mask)") { type(ShopFields.ONB_PASSWORD, "FAKE-password-123") },
        Step("Selesai dan identify") { tap(ShopTargets.ONB_FINISH) },
    ),
)
