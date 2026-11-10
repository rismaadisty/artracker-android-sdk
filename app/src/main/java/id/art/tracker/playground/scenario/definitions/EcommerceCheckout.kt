package id.art.tracker.playground.scenario.definitions

import id.art.tracker.playground.scenario.Capability
import id.art.tracker.playground.scenario.Scenario
import id.art.tracker.playground.scenario.Step
import id.art.tracker.playground.scenario.shop.ShopFields
import id.art.tracker.playground.scenario.shop.ShopRoutes
import id.art.tracker.playground.scenario.shop.ShopTargets

internal val ecommerceCheckout = Scenario(
    id = "ecommerce_checkout",
    title = "Checkout e-commerce",
    description = "Beranda, pencarian, produk, keranjang, pembayaran (kartu dan email di-mask) dan satu panggilan HTTP 404 lokal. " +
        "Menguji screen_view, event bisnis, masking dan replay dengan sentuhan nyata.",
    proves = setOf(Capability.EVENTS, Capability.SCREENS, Capability.MASKING, Capability.REPLAY, Capability.HTTP),
    steps = listOf(
        Step("Buka beranda toko") { navigate(ShopRoutes.HOME) },
        Step("Tunggu frame replay") { awaitReplayFrame() },
        Step("Ketik pencarian") { type(ShopFields.SEARCH, "sepatu") },
        Step("Ketuk Cari") { tap(ShopTargets.SEARCH_GO) },
        Step("Pilih produk") { tap(ShopTargets.PRODUCT_1) },
        Step("Tambah ke keranjang") { tap(ShopTargets.ADD_TO_CART) },
        Step("Menuju checkout") { tap(ShopTargets.GO_CHECKOUT) },
        Step("Isi email dan kartu (di-mask)") {
            type(ShopFields.CHECKOUT_EMAIL, "fake.user@example.invalid")
            type(ShopFields.CHECKOUT_CARD, "4111111111111111")
        },
        Step("Bayar") { tap(ShopTargets.PAY_NOW) },
        Step("Panggilan HTTP 404 ke target lokal") {
            if (ScenarioSdk.impl.httpGet("/status/404") == null) note("target lokal tidak aktif; langkah HTTP dilewati")
        },
        Step("Flush") { ScenarioSdk.impl.flush() },
    ),
)
