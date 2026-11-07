package id.art.tracker.playground.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActionCardTest {
    @get:Rule
    val rule = createComposeRule()

    private val shown = mutableListOf<String>()

    private fun content(block: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalFeedback provides Feedback { shown += it }) { block() }
    }

    @Test
    fun tapRunsActionShowsFeedbackAndCountsPresses() {
        var runs = 0
        content { ActionCard("Track event", "kirim event") { runs++; "tercatat" } }
        rule.onNodeWithText("Track event").performClick()
        rule.onNodeWithText("Track event").performClick()
        assertEquals(2, runs)
        assertEquals(listOf("tercatat", "tercatat"), shown)
        rule.onNodeWithText("2×").assertExists()
    }

    @Test
    fun nullResultShowsNoFeedback() {
        var runs = 0
        content { ActionCard("Flush", "kirim antrean") { runs++; null } }
        rule.onNodeWithText("Flush").performClick()
        assertEquals(1, runs)
        assertEquals(emptyList<String>(), shown)
    }

    @Test
    fun destructiveCardNeedsConfirmation() {
        var runs = 0
        content { ActionCard("Crash now", "mematikan app", destructive = true, confirmText = "App akan crash.") { runs++; "crash" } }
        rule.onNodeWithText("Crash now").performClick()
        assertEquals(0, runs)
        rule.onNodeWithText("Lanjut").assertExists()
        rule.onNodeWithText("Batal").performClick()
        assertEquals(0, runs)
        rule.onNodeWithText("Lanjut").assertDoesNotExist()
        rule.onNodeWithText("Crash now").performClick()
        rule.onNodeWithText("Lanjut").performClick()
        assertEquals(1, runs)
    }

    @Test
    fun disabledCardNeverRunsAndShowsTheReason() {
        var runs = 0
        content { ActionCard("Burst", "banyak event", disabledReason = "dinonaktifkan pada backend nyata") { runs++; "ok" } }
        rule.onNodeWithText("dinonaktifkan pada backend nyata").assertExists()
        rule.onNodeWithText("Burst").performClick()
        assertEquals(0, runs)
        assertEquals(emptyList<String>(), shown)
    }
}
