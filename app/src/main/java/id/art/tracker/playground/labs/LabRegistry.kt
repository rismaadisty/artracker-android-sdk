package id.art.tracker.playground.labs

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import id.art.tracker.R
import id.art.tracker.sample.legacy.LegacyActivity
import id.art.tracker.sample.screens.DiagnosticsScreen
import id.art.tracker.sample.screens.FeedScreen
import id.art.tracker.sample.screens.LoginScreen
import id.art.tracker.sample.screens.PaymentScreen

enum class LabSection { LAB, LEGACY }

/** One entry of the Lab tab. [onOpen] (instead of [content]) is for entries that start an Activity. */
class LabEntry(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val section: LabSection,
    val onOpen: ((Context) -> Unit)? = null,
    val content: @Composable () -> Unit = {},
)

/** Drives the Lab tab. Each lab task appends its entry here; the old demo screens live under LEGACY until Task 15. */
object LabRegistry {
    val entries: List<LabEntry> = listOf(
        LabEntry("legacy_login", R.string.legacy_login, R.string.legacy_login_desc, LabSection.LEGACY, content = { LoginScreen() }),
        LabEntry("legacy_payment", R.string.legacy_payment, R.string.legacy_payment_desc, LabSection.LEGACY, content = { PaymentScreen() }),
        LabEntry("legacy_feed", R.string.legacy_feed, R.string.legacy_feed_desc, LabSection.LEGACY, content = { FeedScreen() }),
        LabEntry("legacy_diagnostics", R.string.legacy_diagnostics, R.string.legacy_diagnostics_desc, LabSection.LEGACY, content = { DiagnosticsScreen() }),
        LabEntry(
            "legacy_xml", R.string.legacy_xml, R.string.legacy_xml_desc, LabSection.LEGACY,
            onOpen = { it.startActivity(Intent(it, LegacyActivity::class.java)) },
        ),
    )

    fun byId(id: String?): LabEntry? = entries.firstOrNull { it.id == id }
}
