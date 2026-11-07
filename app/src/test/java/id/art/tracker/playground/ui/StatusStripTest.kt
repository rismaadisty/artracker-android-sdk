package id.art.tracker.playground.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import id.art.tracker.playground.boot.EndpointBadge
import id.art.tracker.playground.settings.EndpointProfile
import id.art.tracker.playground.status.SdkStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatusStripTest {
    @get:Rule
    val rule = createComposeRule()

    private val sink = EndpointBadge(EndpointProfile.SINK, "localhost", remote = false)
    private val prod = EndpointBadge(EndpointProfile.BACKEND, "artracker.exlm.my.id", remote = true)

    private fun ok(
        initialized: Boolean = true,
        disabled: Boolean = false,
        optedOut: Boolean = false,
    ) = SdkStatus(initialized, "abcdef0123", 3, "accepted", disabled, optedOut, replayIntent = true)

    @Test
    fun showsInactiveWhenNotInitialized() {
        rule.setContent { StatusStrip(ok(initialized = false), sink, null) }
        rule.onNodeWithText("SDK tidak aktif", substring = true).assertExists()
    }

    @Test
    fun showsKillSwitchWhenDisabledWithoutOptOut() {
        rule.setContent { StatusStrip(ok(disabled = true), sink, null) }
        rule.onNodeWithText("dinonaktifkan server", substring = true).assertExists()
    }

    @Test
    fun showsLoadingWhenStatusIsNull() {
        rule.setContent { StatusStrip(null, sink, null) }
        rule.onNodeWithText("memuat", substring = true).assertExists()
    }

    @Test
    fun showsASinkBadgeAndNoRemoteBanner() {
        rule.setContent { StatusStrip(ok(), sink, null) }
        rule.onNodeWithText("SINK", substring = true).assertExists()
        rule.onNodeWithText("PRODUKSI", substring = true).assertDoesNotExist()
    }

    @Test
    fun showsARemoteBannerWithTheHost() {
        rule.setContent { StatusStrip(ok(), prod, null) }
        rule.onNodeWithText("PRODUKSI", substring = true).assertExists()
        rule.onNodeWithText("artracker.exlm.my.id", substring = true).assertExists()
    }

    @Test
    fun showsTheNoticeWhenPresent() {
        rule.setContent { StatusStrip(ok(), sink, "Backend tidak diaktifkan: tidak ada konfirmasi") }
        rule.onNodeWithText("Backend tidak diaktifkan", substring = true).assertExists()
    }
}
