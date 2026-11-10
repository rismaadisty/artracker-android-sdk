package id.art.tracker.playground.ui

import android.app.Application
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.playground.status.PlaygroundControls
import androidx.compose.ui.test.onNodeWithText
import id.art.tracker.playground.labs.CrashLab
import id.art.tracker.playground.labs.EventLab
import id.art.tracker.playground.labs.IdentityLab
import id.art.tracker.playground.labs.NetworkLab
import id.art.tracker.playground.labs.PrivacyLab
import id.art.tracker.playground.labs.QueueLab
import id.art.tracker.playground.labs.ReplayLab
import id.art.tracker.playground.labs.SessionLab
import androidx.compose.ui.test.onNodeWithTag
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

    @Test
    fun privacyLabRendersEveryCase() {
        rule.setContent { PlaygroundTheme { PrivacyLab() } }
        rule.onNodeWithText("Compose: password").assertExists()
        rule.onNodeWithText("WebView").assertExists()
        rule.onNodeWithText("Kirim kunci sensitif").assertExists()
    }

    @Test
    fun replayLabRendersItsSections() {
        rule.setContent { PlaygroundTheme { ReplayLab() } }
        rule.onNodeWithText("Mulai replay").assertExists()
        rule.onNodeWithText("Pohon lebar (> 2000 node)").assertExists()
    }

    @Test
    fun replayLabHasAScreenshotSection_withTheAcceptSwitchOfTheLocalSink() {
        val servers = id.art.tracker.playground.local.LocalServersProvider.instance
        val before = servers.screenshotAllowed
        try {
            servers.screenshotAllowed = true
            rule.setContent { PlaygroundTheme { ReplayLab() } }
            rule.onNodeWithText("Replay screenshot").assertExists()
            rule.onNodeWithText("Ringkasan screenshot").assertExists()
            rule.onNodeWithText("Ekspor screenshot").assertExists()
            rule.onNodeWithTag("lab_screenshot_allowed").performScrollTo().performClick()
            org.junit.Assert.assertFalse(servers.screenshotAllowed)
        } finally {
            servers.screenshotAllowed = before
        }
    }

    @Test
    fun networkLabExplainsWhenTheTargetServerIsNotRunning() {
        rule.setContent { PlaygroundTheme { NetworkLab() } }
        rule.onNodeWithText("Server target lokal", substring = true).assertExists()
    }

    @Test
    fun queueLabRendersBurstsAndFlush() {
        rule.mainClock.autoAdvance = false // the lab polls the status forever
        rule.setContent { PlaygroundTheme { QueueLab() } }
        rule.onNodeWithText("Burst 5000 event").assertExists()
        rule.onNodeWithText("Flush").assertExists()
    }

    @Test
    fun crashLabRendersItsDestructiveActions() {
        rule.setContent { PlaygroundTheme { CrashLab() } }
        rule.onNodeWithText("Crash di main thread").assertExists()
        rule.onNodeWithText("ANR 10 s").assertExists()
        rule.onNodeWithText("Kirim 15 error identik").assertExists()
    }

    @Test
    fun sessionLabRendersTheSessionControls() {
        rule.mainClock.autoAdvance = false // the lab polls the status forever
        rule.setContent { PlaygroundTheme { SessionLab() } }
        rule.onNodeWithText("Reset (logout)").assertExists()
    }

    @Test
    fun identityLabOptOutNeedsConfirmationAndCanBeUndone() {
        val controls = PlaygroundControls.get(ApplicationProvider.getApplicationContext<Application>())
        controls.setOptOut(false)
        rule.setContent { PlaygroundTheme { IdentityLab() } }
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.onNodeWithText("Lanjut").assertExists()
        rule.onNodeWithText("Batal").performClick()
        org.junit.Assert.assertFalse("cancelling must not opt out", controls.optedOut)
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.onNodeWithText("Lanjut").performClick()
        org.junit.Assert.assertTrue(controls.optedOut)
        rule.onNode(isToggleable()).performScrollTo().performClick() // opting back in needs no confirmation
        org.junit.Assert.assertFalse(controls.optedOut)
    }
}
