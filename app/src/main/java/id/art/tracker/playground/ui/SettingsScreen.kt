package id.art.tracker.playground.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import id.art.tracker.R
import id.art.tracker.playground.boot.BackendGrantStore
import id.art.tracker.playground.settings.EndpointProfile
import id.art.tracker.playground.settings.PlaygroundSettings
import id.art.tracker.playground.settings.SettingsEditor
import id.art.tracker.playground.settings.SettingsPreset
import id.art.tracker.playground.settings.SettingsStore

/**
 * Edits [PlaygroundSettings]. "Terapkan & restart" saves them and restarts the process (the SDK config is immutable).
 * Choosing BACKEND on a remote endpoint first asks for the typed phrase; only then is a one-shot grant issued.
 */
@Composable
fun SettingsScreen(
    initial: PlaygroundSettings,
    store: SettingsStore,
    grants: BackendGrantStore,
    buildEndpoint: String,
    realKeyPresent: Boolean,
    onRestart: () -> Unit,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    val editor = remember { SettingsEditor(initial, buildEndpoint, realKeyPresent) }
    var invalidFields by remember { mutableStateOf(setOf<String>()) }
    var confirming by remember { mutableStateOf(false) }
    // Bumped by a preset so every numeric field re-reads the draft (they keep their own text while the user types).
    var presetVersion by remember { mutableIntStateOf(0) }
    val d = editor.draft

    fun apply(issueGrant: Boolean) {
        if (issueGrant) grants.issue(nowMs())
        store.save(editor.draft)
        onRestart()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_presets), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsPreset.values().forEach { p ->
                AssistChip(
                    onClick = {
                        editor.applyPreset(p)
                        invalidFields = emptySet()
                        presetVersion++
                    },
                    label = { Text(p.name) },
                )
            }
        }

        Text(stringResource(R.string.settings_destination), style = MaterialTheme.typography.titleSmall)
        profiles.forEach { (profile, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = d.endpointProfile == profile,
                    onClick = { editor.update { it.copy(endpointProfile = profile) } },
                    role = Role.RadioButton,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = d.endpointProfile == profile, onClick = null)
                Text(stringResource(label), Modifier.padding(start = 8.dp))
            }
        }
        if (d.endpointProfile == EndpointProfile.LOCAL) {
            TextRow(stringResource(R.string.settings_local_endpoint), d.localEndpoint) { v -> editor.update { it.copy(localEndpoint = v) } }
        }
        Text(
            stringResource(if (realKeyPresent) R.string.settings_key_present else R.string.settings_key_missing),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.settings_backend_note), style = MaterialTheme.typography.bodySmall)

        TextRow(stringResource(R.string.settings_environment), d.environment) { v -> editor.update { it.copy(environment = v) } }
        SwitchRow(stringResource(R.string.settings_replay_enabled), d.replayEnabled) { v -> editor.update { it.copy(replayEnabled = v) } }
        NumberRow("replaySampleRate", presetVersion, stringResource(R.string.settings_replay_rate), d.replaySampleRate.toString(), invalidFields, { invalidFields = it }) { text ->
            text.toDoubleOrNull()?.also { v -> editor.update { it.copy(replaySampleRate = v) } } != null
        }
        NumberRow("replayFps", presetVersion, stringResource(R.string.settings_replay_fps), d.replayFps.toString(), invalidFields, { invalidFields = it }) { text ->
            text.toDoubleOrNull()?.also { v -> editor.update { it.copy(replayFps = v) } } != null
        }
        SwitchRow(stringResource(R.string.settings_mask_inputs), d.maskAllInputs) { v -> editor.update { it.copy(maskAllInputs = v) } }
        SwitchRow(stringResource(R.string.settings_mask_text), d.maskAllText) { v -> editor.update { it.copy(maskAllText = v) } }
        NumberRow("networkSampleRate", presetVersion, stringResource(R.string.settings_network_rate), d.networkSampleRate.toString(), invalidFields, { invalidFields = it }) { text ->
            text.toDoubleOrNull()?.also { v -> editor.update { it.copy(networkSampleRate = v) } } != null
        }
        SwitchRow(stringResource(R.string.settings_only_errors), d.captureOnlyErrors) { v -> editor.update { it.copy(captureOnlyErrors = v) } }
        TextRow(stringResource(R.string.settings_ignore_hosts), d.ignoreHosts.joinToString(",")) { v ->
            editor.update { it.copy(ignoreHosts = v.split(',').map(String::trim).filter(String::isNotEmpty).toSet()) }
        }
        NumberRow("flushIntervalMs", presetVersion, stringResource(R.string.settings_flush_interval), d.flushIntervalMs.toString(), invalidFields, { invalidFields = it }) { text ->
            text.toLongOrNull()?.also { v -> editor.update { it.copy(flushIntervalMs = v) } } != null
        }
        NumberRow("sessionTimeoutMs", presetVersion, stringResource(R.string.settings_session_timeout), d.sessionTimeoutMs.toString(), invalidFields, { invalidFields = it }) { text ->
            text.toLongOrNull()?.also { v -> editor.update { it.copy(sessionTimeoutMs = v) } } != null
        }

        editor.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            enabled = editor.canApply && invalidFields.isEmpty(),
            onClick = { if (editor.requiresBackendConfirmation) confirming = true else apply(issueGrant = false) },
        ) { Text(stringResource(R.string.settings_apply)) }
    }

    if (confirming) {
        BackendConfirmDialog(
            host = editor.confirmationHost,
            accepted = editor::confirmationAccepted,
            onConfirm = {
                confirming = false
                apply(issueGrant = true)
            },
            onDismiss = { confirming = false },
        )
    }
}

private val profiles = listOf(
    EndpointProfile.SINK to R.string.profile_sink,
    EndpointProfile.OFFLINE to R.string.profile_offline,
    EndpointProfile.LOCAL to R.string.profile_local,
    EndpointProfile.BACKEND to R.string.profile_backend,
)

@Composable
private fun BackendConfirmDialog(host: String, accepted: (String) -> Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backend_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backend_confirm_body, host))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.backend_confirm_hint)) },
                    singleLine = true,
                    modifier = Modifier.testTag("confirm_phrase"),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = accepted(typed), onClick = onConfirm) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TextRow(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** A numeric field that keeps its own text; [apply] returns false for text that does not parse (the field is then flagged). */
@Composable
private fun NumberRow(
    key: String,
    presetVersion: Int,
    label: String,
    initial: String,
    invalid: Set<String>,
    setInvalid: (Set<String>) -> Unit,
    apply: (String) -> Boolean,
) {
    var text by remember(presetVersion) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            setInvalid(if (apply(it)) invalid - key else invalid + key)
        },
        label = { Text(label) },
        singleLine = true,
        isError = key in invalid,
        supportingText = if (key in invalid) ({ Text(stringResource(R.string.settings_invalid_number)) }) else null,
        modifier = Modifier.fillMaxWidth().testTag("field_$key"),
    )
}
