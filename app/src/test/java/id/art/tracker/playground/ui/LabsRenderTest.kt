package id.art.tracker.playground.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import id.art.tracker.playground.labs.EventLab
import id.art.tracker.playground.labs.IdentityLab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Render smoke tests for the lab screens (a composition error in a lab would otherwise only show on a device). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LabsRenderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun eventLabRenders() {
        rule.setContent { PlaygroundTheme { EventLab() } }
        rule.onNodeWithText("Kirim event").assertExists()
        rule.onNodeWithText("Log FATAL").assertExists()
    }

    @Test
    fun identityLabRenders() {
        rule.setContent { PlaygroundTheme { IdentityLab() } }
        rule.onNodeWithText("Reset (logout)").assertExists()
    }
}
