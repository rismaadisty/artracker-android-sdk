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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.R
import id.art.tracker.playground.boot.BootState
import id.art.tracker.playground.status.PlaygroundControls
import id.art.tracker.playground.status.SdkStatus
import id.art.tracker.playground.status.SdkStatusRepository
import id.art.tracker.playground.ui.ActionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BURSTS = listOf(100, 1_000, 5_000)

/** Event bursts to fill the SDK's persistent queue (it evicts at 5 000 rows), with live `pending` and `lastSend`. */
@Composable
fun QueueLab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { SdkStatusRepository(controls = PlaygroundControls.get(context)) }
    var status by remember { mutableStateOf<SdkStatus?>(null) }
    LaunchedEffect(Unit) {
        while (isActive) {
            status = withContext(Dispatchers.IO) { repo.poll() }
            delay(1_000)
        }
    }
    val remote = BootState.resolved?.badge?.remote ?: false
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    val blocked = LabGuard.disabledReason(LabActionKind.LOAD, remote)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.lab_queue_live, status?.pending ?: 0, status?.lastSend ?: "-", progress),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(stringResource(R.string.lab_queue_hint), style = MaterialTheme.typography.bodySmall)
        BURSTS.forEach { n ->
            val decision = BurstPolicy.decide(n, remote)
            ActionCard(
                title = stringResource(R.string.lab_queue_burst, n),
                description = stringResource(R.string.lab_queue_burst_desc),
                destructive = decision == BurstDecision.CONFIRM,
                confirmText = stringResource(R.string.lab_queue_confirm, n),
                disabledReason = blocked,
            ) {
                job?.cancel()
                progress = 0
                job = scope.launch(Dispatchers.Default) {
                    BurstRunner { i -> ARTracker.track("burst_event", mapOf("i" to i)) }.run(BurstPolicy.clamp(n)) { progress = it }
                }
                "burst $n event dimulai"
            }
        }
        ActionCard(stringResource(R.string.lab_queue_stop), stringResource(R.string.lab_queue_stop_desc)) {
            job?.cancel()
            "burst dihentikan"
        }
        ActionCard(stringResource(R.string.lab_queue_flush), stringResource(R.string.lab_queue_flush_desc)) {
            ARTracker.flush()
            "flush diminta"
        }
    }
}
