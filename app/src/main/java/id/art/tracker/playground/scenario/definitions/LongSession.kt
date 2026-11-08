package id.art.tracker.playground.scenario.definitions

import id.art.tracker.playground.scenario.Capability
import id.art.tracker.playground.scenario.Scenario
import id.art.tracker.playground.scenario.Step

internal val longSession = Scenario(
    id = "long_session",
    title = "Sesi panjang",
    description = "Penanda sebelum dan sesudah app ditinggal lebih lama dari sessionTimeoutMs; sesi harus berganti. " +
        "Pakai preset DEBUG_FAST agar cukup menunggu beberapa detik.",
    proves = setOf(Capability.SESSION, Capability.EVENTS),
    steps = listOf(
        Step("Penanda sebelum background") { ScenarioSdk.impl.track("session_marker_before") },
        Step("Tinggalkan app") {
            awaitUser("Tekan Home, tunggu lebih lama dari sessionTimeoutMs (lihat Lab Sesi), kembali ke app, lalu tekan Lanjut")
        },
        Step("Penanda setelah kembali") { ScenarioSdk.impl.track("session_marker_after") },
        Step("Flush") { ScenarioSdk.impl.flush() },
    ),
)
