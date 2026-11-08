package id.art.tracker.playground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.art.tracker.R
import id.art.tracker.playground.boot.EndpointBadge
import id.art.tracker.playground.status.SdkStatus

/** Where events go (always visible), the SDK state, and any boot notice (e.g. a refused backend request). */
@Composable
fun StatusStrip(status: SdkStatus?, badge: EndpointBadge, notice: String?, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    Column(modifier.fillMaxWidth()) {
        if (badge.remote) {
            Text(
                stringResource(R.string.banner_remote, badge.host),
                color = colors.onRemote,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth().background(colors.remote).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            Text(
                stringResource(R.string.badge_safe, badge.profile.name, badge.host),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        val line = when {
            status == null -> stringResource(R.string.status_loading)
            !status.initialized -> stringResource(R.string.status_inactive)
            status.disabledByOtherThanOptOut -> stringResource(R.string.status_server_disabled)
            else -> stringResource(
                R.string.status_line,
                status.sessionId?.take(8) ?: "-",
                status.pending,
                status.lastSend ?: "-",
                stringResource(if (status.replayIntent) R.string.status_on else R.string.status_off),
            ) + if (status.optedOutIntent) " · " + stringResource(R.string.status_opted_out) else ""
        }
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = colors.retry,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
}
