package id.art.tracker.playground.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
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

    /** A touch click on a chip right after a text field was edited is swallowed by the Robolectric harness; the action is not. */
    private fun clickPreset(name: String) {
        rule.onNodeWithText(name).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun assertFieldText(tag: String, expected: String) =
        rule.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))

    @Test
    fun aPresetUpdatesTheNumericFieldsSoTheScreenMatchesWhatIsSaved() {
        show()
        clickPreset("DEBUG_FAST")
        assertFieldText("field_flushIntervalMs", "1000") // exact: "10000" would also *contain* "1000"
        assertFieldText("field_sessionTimeoutMs", "10000")
        rule.onNodeWithText("Terapkan & restart").performScrollTo().performClick()
        assertEquals(1_000L, saved.single().flushIntervalMs)
        assertEquals(10_000L, saved.single().sessionTimeoutMs)
    }

    @Test
    fun aPresetClearsAnEarlierTypoInThatField() {
        show()
        rule.onNodeWithTag("field_flushIntervalMs").performScrollTo().performTextReplacement("abc")
        rule.onNodeWithText("Angka tidak valid").assertExists()
        rule.onNodeWithText("Terapkan & restart").performScrollTo().assertIsNotEnabled()
        clickPreset("DEBUG_FAST")
        assertFieldText("field_flushIntervalMs", "1000")
        rule.onNodeWithText("Angka tidak valid").assertDoesNotExist()
        rule.onNodeWithText("Terapkan & restart").performScrollTo().assertIsEnabled()
    }

    @Test
    fun aPresetThatKeepsTheSameValueStillClearsATypo() {
        show()
        rule.onNodeWithTag("field_flushIntervalMs").performScrollTo().performTextReplacement("abc")
        clickPreset("DEFAULT") // DEFAULT keeps the draft's 10000, so only the version bump can reset the field
        assertFieldText("field_flushIntervalMs", "10000")
        rule.onNodeWithText("Angka tidak valid").assertDoesNotExist()
    }

    @Test
    fun typingAValidNumberIsNotRewrittenWhileTheUserTypes() {
        show()
        rule.onNodeWithTag("field_replaySampleRate").performScrollTo().performTextReplacement("0.")
        assertFieldText("field_replaySampleRate", "0.") // "0." parses to 0.0 but must not become "0.0" under the cursor
    }

    @Test
    fun replayQuality_hasAChoice_andItIsSaved() {
        show()
        rule.onNodeWithText("foto layar asli", substring = true).assertExists()
        rule.onNodeWithText("STANDARD").assertExists()
        rule.onNodeWithText("HIGH").performScrollTo().performClick()
        rule.onNodeWithText("Terapkan & restart").performScrollTo().performClick()
        assertEquals(id.art.tracker.ReplayQuality.HIGH, saved.last().replayQuality)
    }
}
