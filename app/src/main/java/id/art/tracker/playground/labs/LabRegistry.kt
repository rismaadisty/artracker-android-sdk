package id.art.tracker.playground.labs

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import id.art.tracker.R
import id.art.tracker.sample.legacy.LegacyActivity

/** One entry of the Lab tab. [onOpen] (instead of [content]) is for entries that start an Activity. */
class LabEntry(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val onOpen: ((Context) -> Unit)? = null,
    val content: @Composable () -> Unit = {},
)

/** Drives the Lab tab. */
object LabRegistry {
    val entries: List<LabEntry> = listOf(
        LabEntry("event", R.string.lab_event, R.string.lab_event_desc, content = { EventLab() }),
        LabEntry("privacy", R.string.lab_privacy, R.string.lab_privacy_desc, content = { PrivacyLab() }),
        LabEntry("replay", R.string.lab_replay, R.string.lab_replay_desc, content = { ReplayLab() }),
        LabEntry("network", R.string.lab_network, R.string.lab_network_desc, content = { NetworkLab() }),
        LabEntry("queue", R.string.lab_queue, R.string.lab_queue_desc, content = { QueueLab() }),
        LabEntry("crash", R.string.lab_crash, R.string.lab_crash_desc, content = { CrashLab() }),
        LabEntry("session", R.string.lab_session, R.string.lab_session_desc, content = { SessionLab() }),
        LabEntry("identity", R.string.lab_identity, R.string.lab_identity_desc, content = { IdentityLab() }),
        LabEntry(
            "legacy_xml", R.string.legacy_xml, R.string.legacy_xml_desc,
            onOpen = { it.startActivity(Intent(it, LegacyActivity::class.java)) },
        ),
    )

    fun byId(id: String?): LabEntry? = entries.firstOrNull { it.id == id }
}
