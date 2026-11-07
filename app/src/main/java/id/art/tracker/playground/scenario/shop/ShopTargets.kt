package id.art.tracker.playground.scenario.shop

/** Tap targets the mock shop screens register; scenarios refer to them by these ids. */
object ShopTargets {
    const val SEARCH_GO = "shop_search_go"
    const val PRODUCT_1 = "product_1"
    const val ADD_TO_CART = "add_to_cart"
    const val GO_CHECKOUT = "go_checkout"
    const val PAY_NOW = "pay_now"
    const val ONB_NEXT_1 = "onb_next_1"
    const val ONB_NEXT_2 = "onb_next_2"
    const val ONB_FINISH = "onb_finish"
    const val STUCK_BUTTON = "stuck_button"

    val ALL: Set<String> = setOf(SEARCH_GO, PRODUCT_1, ADD_TO_CART, GO_CHECKOUT, PAY_NOW, ONB_NEXT_1, ONB_NEXT_2, ONB_FINISH, STUCK_BUTTON)
}

/** Text fields (see `ScenarioTextField`) the scenarios type into. */
object ShopFields {
    const val SEARCH = "shop_search"
    const val CHECKOUT_EMAIL = "checkout_email"
    const val CHECKOUT_CARD = "checkout_card"
    const val ONB_NAME = "onb_name"
    const val ONB_EMAIL = "onb_email"
    const val ONB_PASSWORD = "onb_password"
}

object ShopRoutes {
    const val HOME = "shop/home"
    const val PRODUCTS = "shop/products"
    const val PRODUCT = "shop/product"
    const val CART = "shop/cart"
    const val CHECKOUT = "shop/checkout"
    const val RESULT = "shop/result"
    const val ONBOARDING = "shop/onboarding"
    const val STUCK = "shop/stuck"
}
