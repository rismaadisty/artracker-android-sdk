package id.art.tracker.playground.scenario.definitions

import id.art.tracker.playground.scenario.Capability
import id.art.tracker.playground.scenario.Scenario
import id.art.tracker.playground.scenario.Step

internal val offlineUser = Scenario(
    id = "offline_user",
    title = "Pengguna offline",
    description = "Aktivitas saat mode pesawat aktif (antrean menumpuk), lalu jaringan kembali dan antrean dikirim. " +
        "Mode pesawat tidak bisa diubah dari app, jadi dua langkah menunggu Anda.",
    proves = setOf(Capability.OFFLINE, Capability.QUEUE, Capability.EVENTS),
    steps = listOf(
        Step("Aktifkan mode pesawat") { awaitUser("Aktifkan mode pesawat, lalu tekan Lanjut") },
        Step("Aktivitas saat offline") {
            ScenarioSdk.impl.screen("OfflineHome")
            repeat(3) { ScenarioSdk.impl.track("offline_action") }
        },
        Step("Matikan mode pesawat") { awaitUser("Matikan mode pesawat, lalu tekan Lanjut") },
        Step("Flush antrean") { ScenarioSdk.impl.flush() },
    ),
)
