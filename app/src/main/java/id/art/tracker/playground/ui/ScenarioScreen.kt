package id.art.tracker.playground.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.art.tracker.R
import id.art.tracker.playground.scenario.RunState
import id.art.tracker.playground.scenario.Scenario
import id.art.tracker.playground.scenario.ScenarioHost
import id.art.tracker.playground.scenario.definitions.Scenarios

/** The six scripted scenarios: what each proves, Run/Stop, step progress, warnings, and the "Lanjut" gate. */
@Composable
fun ScenarioScreen(host: ScenarioHost, scenarios: List<Scenario> = Scenarios.all) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(scenarios) { s ->
            val mine = host.active == s.id
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium)
                    Text(s.description, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.proves.forEach { SuggestionChip(onClick = {}, label = { Text(it.name) }) }
                    }
                    if (mine) StateLines(host)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { host.start(s) }) { Text(stringResource(R.string.scenario_run)) }
                        if (mine && host.state is RunState.Running) {
                            OutlinedButton(onClick = { host.stop() }) { Text(stringResource(R.string.scenario_stop)) }
                        }
                        if (mine && host.awaiting != null) {
                            Button(onClick = { host.continueFromUser() }) { Text(stringResource(R.string.action_continue)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StateLines(host: ScenarioHost) {
    when (val st = host.state) {
        RunState.Idle -> Unit
        is RunState.Running -> Text(stringResource(R.string.scenario_step, st.index + 1, st.total, st.label), style = MaterialTheme.typography.labelMedium)
        is RunState.Done -> {
            Text(stringResource(R.string.scenario_done), style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.accepted)
            st.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.retry) }
        }
        is RunState.Failed -> Text(
            stringResource(R.string.scenario_failed, st.index + 1, st.message),
            style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.error,
        )
        RunState.Cancelled -> Text(stringResource(R.string.scenario_cancelled), style = MaterialTheme.typography.labelMedium)
    }
    host.awaiting?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
