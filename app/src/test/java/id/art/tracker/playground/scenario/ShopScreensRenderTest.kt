package id.art.tracker.playground.scenario

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import id.art.tracker.playground.scenario.shop.Cart
import id.art.tracker.playground.scenario.shop.Checkout
import id.art.tracker.playground.scenario.shop.OnboardingScreen
import id.art.tracker.playground.scenario.shop.ProductDetail
import id.art.tracker.playground.scenario.shop.ProductList
import id.art.tracker.playground.scenario.shop.ShopHome
import id.art.tracker.playground.scenario.shop.ShopTargets
import id.art.tracker.playground.scenario.shop.StuckScreen
import id.art.tracker.playground.ui.PlaygroundTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every tap target a scenario uses must really be registered by the screen that is supposed to hold it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShopScreensRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(screen: @Composable () -> Unit) {
        rule.setContent { PlaygroundTheme { screen() } }
        rule.waitForIdle()
    }

    private fun assertRegistered(vararg ids: String) {
        ids.forEach {
            val t = TapRegistry.Shared.find(it)
            assertNotNull("target '$it' was not registered", t)
            assertTrue("target '$it' must be in the activity window", t!!.inActivityWindow)
        }
    }

    @Test
    fun shopHomeRegistersItsTargets() {
        show { ShopHome(onGo = {}) }
        assertRegistered(ShopTargets.SEARCH_GO)
    }

    @Test
    fun productListRegistersTheFirstProduct() {
        show { ProductList(onGo = {}) }
        assertRegistered(ShopTargets.PRODUCT_1)
    }

    @Test
    fun productDetailRegistersAddToCart() {
        show { ProductDetail(onGo = {}) }
        assertRegistered(ShopTargets.ADD_TO_CART)
    }

    @Test
    fun cartRegistersGoCheckout() {
        show { Cart(onGo = {}) }
        assertRegistered(ShopTargets.GO_CHECKOUT)
    }

    @Test
    fun checkoutRegistersPay() {
        show { Checkout(onGo = {}) }
        assertRegistered(ShopTargets.PAY_NOW)
    }

    @Test
    fun onboardingRegistersItsFirstStep() {
        show { OnboardingScreen(onDone = {}) }
        assertRegistered(ShopTargets.ONB_NEXT_1)
    }

    @Test
    fun stuckScreenRegistersTheDeadButton() {
        show { StuckScreen() }
        assertRegistered(ShopTargets.STUCK_BUTTON)
    }
}
