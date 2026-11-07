package id.art.tracker.playground.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import id.art.tracker.playground.boot.BackendGrantStore
import id.art.tracker.playground.settings.EndpointProfile
import id.art.tracker.playground.settings.PlaygroundSettings
import id.art.tracker.playground.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val saved = mutableListOf<PlaygroundSettings>()
    private val grants = mutableListOf<Long>()
    private var restarts = 0

    private val store = object : SettingsStore {
        override fun load() = PlaygroundSettings.DEFAULT
        override fun save(settings: PlaygroundSettings) { saved += settings }
    }
    private val grantStore = object : BackendGrantStore {
        override fun issue(nowMs: Long) { grants += nowMs }
        override fun consume(nowMs: Long) = false
    }

    private fun show(buildEndpoint: String = "https://artracker.exlm.my.id/", realKey: Boolean = true) = rule.setContent {
        SettingsScreen(
            initial = PlaygroundSettings.DEFAULT, store = store, grants = grantStore,
            buildEndpoint = buildEndpoint, realKeyPresent = realKey, onRestart = { restarts++ }, nowMs = { 42L },
        )
    }

    private fun chooseBackendAndApply() {
        rule.onNodeWithText("produksi (berisiko)", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Terapkan & restart").performScrollTo().performClick()
    }

    @Test
    fun applyIsDisabledUntilSomethingChanges() {
        show()
        rule.onNodeWithText("Terapkan & restart").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun cancellingTheBackendDialogSavesNothingAndIssuesNoGrant() {
        show()
        chooseBackendAndApply()
        rule.onNodeWithText("Batal").performClick()
        assertEquals(0, saved.size)
        assertEquals(0, grants.size)
        assertEquals(0, restarts)
    }

    @Test
    fun theBackendNeedsTheExactPhraseBeforeAGrantIsIssued() {
        show()
        chooseBackendAndApply()
        rule.onNodeWithText("Lanjut").assertIsNotEnabled()
        rule.onNode(hasTestTag("confirm_phrase")).performTextInput("kirim")
        rule.onNodeWithText("Lanjut").assertIsNotEnabled()
        assertEquals(0, grants.size)
    }

    @Test
    fun confirmingWithTheRightPhraseIssuesOneGrantSavesAndRestarts() {
        show()
        chooseBackendAndApply()
        rule.onNode(hasTestTag("confirm_phrase")).performTextInput("KIRIM")
        rule.onNodeWithText("Lanjut").assertIsEnabled().performClick()
        assertEquals(listOf(42L), grants)
        assertEquals(1, saved.size)
        assertEquals(EndpointProfile.BACKEND, saved.single().endpointProfile)
        assertEquals(1, restarts)
    }

    @Test
    fun aSafeProfileAppliesWithoutAnyDialogOrGrant() {
        show()
        rule.onNodeWithText("OFFLINE", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Terapkan & restart").performScrollTo().performClick()
        assertEquals(0, grants.size)
        assertEquals(EndpointProfile.OFFLINE, saved.single().endpointProfile)
        assertEquals(1, restarts)
    }

    @Test
    fun backendWithoutARealKeyCannotBeApplied() {
        show(realKey = false)
        rule.onNodeWithText("produksi (berisiko)", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Terapkan & restart").performScrollTo().assertIsNotEnabled()
    }
}
