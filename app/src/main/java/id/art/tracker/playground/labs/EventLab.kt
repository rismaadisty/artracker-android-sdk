package id.art.tracker.playground.labs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.LogLevel
import id.art.tracker.R
import id.art.tracker.playground.boot.BootState
import id.art.tracker.playground.ui.ActionCard

/** `track`, manual `screen`, all five log levels, `captureException` and the hostile-payload cards. */
@Composable
fun EventLab(modifier: Modifier = Modifier) {
    val remote = BootState.resolved?.badge?.remote ?: false
    var name by remember { mutableStateOf("sample_event") }
    val rows = remember { mutableStateListOf(PropertyRow("source", PropertyType.STRING, "event_lab")) }
    var screenName by remember { mutableStateOf("ManualScreen") }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.lab_event_section_custom), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text(stringResource(R.string.lab_event_name)) }, modifier = Modifier.fillMaxWidth(),
        )
        rows.forEachIndexed { index, row ->
            PropertyRowEditor(row, onChange = { rows[index] = it }, onRemove = { rows.removeAt(index) })
        }
        OutlinedButton(onClick = { rows.add(PropertyRow("", PropertyType.STRING, "")) }) {
            Text(stringResource(R.string.lab_event_add_property))
        }
        ActionCard(stringResource(R.string.lab_event_send), stringResource(R.string.lab_event_send_desc)) {
            when (val result = PropertyBuilder.build(rows.toList())) {
                is BuildResult.Error -> "Baris ${result.row + 1}: ${result.message}"
                is BuildResult.Ok -> {
                    ARTracker.track(name, result.map)
                    "track `$name` → antre"
                }
            }
        }

        Text(stringResource(R.string.lab_event_section_screen), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = screenName, onValueChange = { screenName = it }, singleLine = true,
            label = { Text(stringResource(R.string.lab_event_screen_name)) }, modifier = Modifier.fillMaxWidth(),
        )
        ActionCard(stringResource(R.string.lab_event_screen_send), stringResource(R.string.lab_event_screen_desc)) {
            ARTracker.screen(screenName)
            "screen `$screenName` → antre"
        }

        Text(stringResource(R.string.lab_event_section_logs), style = MaterialTheme.typography.titleSmall)
        LogLevel.values().forEach { level ->
            ActionCard(stringResource(R.string.lab_event_log, level.name), stringResource(R.string.lab_event_log_desc)) {
                ARTracker.log(level, "Pesan uji ${level.name}", mapOf("lab" to "event"))
                "log ${level.name} → antre"
            }
        }
        ActionCard(stringResource(R.string.lab_event_capture), stringResource(R.string.lab_event_capture_desc)) {
            ARTracker.captureException(IllegalStateException("Sample handled error"), mapOf("lab" to "event"))
            "exception → antre"
        }

        Text(stringResource(R.string.lab_event_section_hostile), style = MaterialTheme.typography.titleSmall)
        HostilePayloads.all.forEach { payload ->
            ActionCard(
                title = stringResource(R.string.lab_event_hostile, payload.name),
                description = stringResource(R.string.lab_event_hostile_desc),
                disabledReason = LabGuard.disabledReason(LabActionKind.FAKE_PII, remote),
            ) {
                ARTracker.track("hostile_${payload.name}", payload.properties)
                "track `hostile_${payload.name}` → antre"
            }
        }
    }
}

@Composable
private fun PropertyRowEditor(row: PropertyRow, onChange: (PropertyRow) -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = row.key, onValueChange = { onChange(row.copy(key = it)) }, singleLine = true,
            label = { Text(stringResource(R.string.lab_event_key)) }, modifier = Modifier.weight(1f),
        )
        AssistChip(
            onClick = {
                val types = PropertyType.values()
                onChange(row.copy(type = types[(row.type.ordinal + 1) % types.size]))
            },
            label = { Text(row.type.name) },
        )
        OutlinedTextField(
            value = row.raw, onValueChange = { onChange(row.copy(raw = it)) }, singleLine = true,
            label = { Text(stringResource(R.string.lab_event_value)) }, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text("✕") }
    }
}
