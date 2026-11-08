package id.art.tracker.playground.labs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.R
import id.art.tracker.playground.settings.SharedPrefsSettingsStore
import id.art.tracker.playground.status.PlaygroundControls
import id.art.tracker.playground.status.PlaygroundSessions
import id.art.tracker.playground.status.SdkStatus
import id.art.tracker.playground.status.SdkStatusRepository
import id.art.tracker.playground.ui.ActionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** The current session, how long to wait in the background to rotate it, `reset()` and the ids seen so far. */
@Composable
fun SessionLab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeoutMs = remember { SharedPrefsSettingsStore(context).load().sessionTimeoutMs }
    val repo = remember { SdkStatusRepository(controls = PlaygroundControls.get(context)) }
    var status by remember { mutableStateOf<SdkStatus?>(null) }
    LaunchedEffect(Unit) {
        while (isActive) {
            status = withContext(Dispatchers.IO) { repo.poll() }
            PlaygroundSessions.history.observe(status?.sessionId)
            delay(1_000)
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.lab_session_current, status?.sessionId ?: "-"), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.lab_session_timeout, timeoutMs), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.lab_session_advice, SessionAdvice.waitSeconds(timeoutMs)),
            style = MaterialTheme.typography.bodyMedium,
        )
        ActionCard(stringResource(R.string.lab_identity_reset), stringResource(R.string.lab_identity_reset_desc)) {
            ARTracker.reset()
            "reset → sesi baru"
        }
        Text(stringResource(R.string.lab_session_history), style = MaterialTheme.typography.titleSmall)
        PlaygroundSessions.history.ids.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
