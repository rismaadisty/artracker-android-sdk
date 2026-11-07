package id.art.tracker.playground.ui

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import id.art.tracker.playground.inspector.EventRing
import id.art.tracker.playground.inspector.InspectedItem
import id.art.tracker.playground.inspector.ItemSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InspectorScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun item(name: String, source: ItemSource, type: String = "event") = InspectedItem(
        id = "id-$name", type = type, name = name, level = null, message = null, timestampMs = 0L, sessionId = "s",
        userId = null, attributes = mapOf("k" to "v"), truncated = false, source = source,
    )

    private fun show(ring: EventRing, mode: ItemSource) =
        rule.setContent { InspectorScreen(ring = ring, mode = mode, onRefresh = {}, autoRefreshMs = 0) }

    @Test
    fun sentPayloadModeShowsItsBannerAndItems() {
        val ring = EventRing()
        ring.add(item("login_clicked", ItemSource.SENT_PAYLOAD))
        ring.add(item("checkout_clicked", ItemSource.SENT_PAYLOAD))
        show(ring, ItemSource.SENT_PAYLOAD)
        rule.onNodeWithText("payload yang dikirim SDK, setelah redaksi", substring = true).assertExists()
        rule.onNodeWithText("login_clicked").assertExists()
        rule.onNodeWithText("checkout_clicked").assertExists()
    }

    @Test
    fun metadataModeSaysValuesAreNotShown() {
        val ring = EventRing()
        ring.add(item("login_clicked", ItemSource.METADATA_ONLY))
        show(ring, ItemSource.METADATA_ONLY)
        rule.onNodeWithText("mode metadata", substring = true, ignoreCase = true).assertExists()
        rule.onNodeWithText("nilai tidak ditampilkan").assertExists()
    }

    @Test
    fun searchKeepsOnlyMatchingRows() {
        val ring = EventRing()
        ring.add(item("login_clicked", ItemSource.SENT_PAYLOAD))
        ring.add(item("checkout_clicked", ItemSource.SENT_PAYLOAD))
        show(ring, ItemSource.SENT_PAYLOAD)
        rule.onNode(hasTestTag("search")).performTextInput("login")
        rule.onNodeWithText("login_clicked").assertExists()
        rule.onNodeWithText("checkout_clicked").assertDoesNotExist()
    }

    @Test
    fun clearEmptiesTheList() {
        val ring = EventRing()
        ring.add(item("login_clicked", ItemSource.SENT_PAYLOAD))
        show(ring, ItemSource.SENT_PAYLOAD)
        rule.onNodeWithText("Hapus").performClick()
        rule.onNodeWithText("login_clicked").assertDoesNotExist()
    }

    @Test
    fun noControlRevealsRawValues() {
        show(EventRing(), ItemSource.SENT_PAYLOAD)
        rule.onNodeWithText("tampilkan nilai", substring = true, ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("raw", substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
