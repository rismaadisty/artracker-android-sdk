package id.art.tracker.playground.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The host polls forever (status loop), so the test clock must not auto-advance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaygroundHostSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rendersTheFourTabsAndSwitchesToTheLabList() {
        rule.mainClock.autoAdvance = false
        rule.setContent { PlaygroundTheme { PlaygroundHost() } }
        rule.onNodeWithText("Skenario").assertExists()
        rule.onNodeWithText("Lab").assertExists()
        rule.onNodeWithText("Inspector").assertExists()
        rule.onNodeWithText("Pengaturan").assertExists()
        rule.onNodeWithText("Lab").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Lab per kapabilitas").assertExists()
        rule.onNodeWithText("Event & log").assertExists()
    }
}
