package id.art.tracker.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.art.tracker.ARTracker
import id.art.tracker.MainActivity
import id.art.tracker.TestPlaygroundApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScenarioFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ingest get() = TestPlaygroundApplication.ingest

    @Before
    fun clean() {
        ARTracker.startReplay()
        ingest.clear()
    }

    /** Runs the first scenario card (ecommerce_checkout) to the end through the real UI. */
    private fun runEcommerce() {
        rule.onAllNodesWithText("Jalankan")[0].performClick()
        rule.waitUntil(90_000) {
            rule.onAllNodesWithText("Selesai").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Gagal", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("the scenario failed", rule.onAllNodesWithText("Gagal", substring = true).fetchSemanticsNodes().isEmpty())
        ARTracker.stopReplay()
        ARTracker.flush()
    }

    @Test
    fun ecommerceScenarioProducesExpectedItems() {
        runEcommerce()
        val seen = ingest.awaitUntil(20_000) { it.chunks().isNotEmpty() && it.hasItemNamed("http_request") }
        val items = seen.items()
        val screens = items.filter { it.optString("name") == "screen_view" }.map { it.optJSONObject("attributes")?.optString("screen").orEmpty() }
        for (route in listOf("shop/home", "shop/products", "shop/product", "shop/cart", "shop/checkout", "shop/result")) {
            assertTrue("missing screen_view for $route in $screens", route in screens)
        }
        assertTrue(items.any { it.optString("name") == "http_request" && it.optString("level") == "warn" })
        assertTrue("at least one replay chunk expected", seen.chunks().isNotEmpty())
        assertTrue("typed taps must be recorded as touches", seen.chunks().touches().isNotEmpty())
    }

    @Test
    fun typedSecretsNeverAppearInReplayChunks() {
        runEcommerce()
        val seen = ingest.awaitUntil(20_000) { it.chunks().isNotEmpty() }
        val chunks = seen.chunks()
        assertTrue("without chunks this check would be vacuous", chunks.isNotEmpty())
        val text = chunks.joinToString("\n") { it.toString() }
        assertFalse("the typed card number leaked into a replay chunk", text.contains("4111111111111111"))
        assertFalse("the typed email leaked into a replay chunk", text.contains("fake.user@example.invalid"))
    }
}
