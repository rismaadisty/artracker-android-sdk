package id.art.tracker.playground.scenario

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScenarioFieldsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun setTypesIntoARegisteredFieldAndShowsTheText() {
        rule.setContent { ScenarioTextField(id = "email", label = "Email") }
        rule.waitForIdle()
        assertTrue(ScenarioFields.set("email", "fake.user@example.invalid"))
        rule.waitForIdle()
        rule.onNodeWithText("fake.user@example.invalid").assertExists()
    }

    @Test
    fun setOnAnUnknownFieldReturnsFalse() {
        assertFalse(ScenarioFields.set("no-such-field", "x"))
    }
}
