package id.art.tracker.playground.scenario.definitions

import id.art.tracker.playground.scenario.Capability
import id.art.tracker.playground.scenario.Scenario
import id.art.tracker.playground.scenario.Step
import id.art.tracker.playground.scenario.shop.ShopRoutes
import id.art.tracker.playground.scenario.shop.ShopTargets
import kotlinx.coroutines.delay

internal val frustratedTaps = Scenario(
    id = "frustrated_taps",
    title = "Ketukan beruntun di tombol macet",
    description = "Delapan ketukan cepat pada tombol yang tidak merespons, lalu satu error tertangani. " +
        "SDK menandai chunk replay yang memuat tiga ketukan cepat di satu titik dengan has_rage_click; " +
        "selain itu yang terekam adalah sentuhan di replay dan error yang dilaporkan.",
    proves = setOf(Capability.ERRORS, Capability.REPLAY),
    steps = listOf(
        Step("Buka halaman macet") { navigate(ShopRoutes.STUCK) },
        Step("Tunggu snapshot replay") { awaitReplayFrame() },
        Step("Ketuk tombol 8 kali") {
            repeat(8) {
                tap(ShopTargets.STUCK_BUTTON)
                delay(120)
            }
        },
        Step("Laporkan error tertangani") { ScenarioSdk.impl.captureException(IllegalStateException("Tombol tidak merespons")) },
    ),
)
