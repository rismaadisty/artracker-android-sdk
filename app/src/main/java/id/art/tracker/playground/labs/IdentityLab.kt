package id.art.tracker.playground.labs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.R
import id.art.tracker.playground.status.PlaygroundControls
import id.art.tracker.playground.ui.ActionCard
import id.art.tracker.playground.ui.ConfirmDialog

/**
 * `identify` as one of three personas, `reset` (logout: forgets the user, starts a new session) and the opt-out switch
 * (stops recording and sending, and deletes everything queued, so enabling it asks first).
 */
@Composable
fun IdentityLab(modifier: Modifier = Modifier) {
    val controls = PlaygroundControls.get(LocalContext.current)
    var optedOut by remember { mutableStateOf(controls.optedOut) }
    var confirming by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.lab_identity_section), style = MaterialTheme.typography.titleSmall)
        Personas.all.forEach { persona ->
            ActionCard(
                title = stringResource(R.string.lab_identity_as, persona.label),
                description = persona.traits.entries.joinToString { "${it.key}=${it.value}" },
            ) {
                ARTracker.identify(persona.userId, persona.traits)
                "identify ${persona.userId} → antre"
            }
        }
        ActionCard(stringResource(R.string.lab_identity_reset), stringResource(R.string.lab_identity_reset_desc)) {
            ARTracker.reset()
            "reset → sesi baru"
        }

        Text(stringResource(R.string.lab_identity_optout_section), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.lab_identity_optout), Modifier.weight(1f))
            Switch(
                checked = optedOut,
                onCheckedChange = { enable ->
                    if (enable) {
                        confirming = true
                    } else {
                        controls.setOptOut(false)
                        optedOut = false
                    }
                },
            )
        }
        Text(stringResource(R.string.lab_identity_optout_desc), style = MaterialTheme.typography.bodySmall)
    }

    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.lab_identity_optout),
            text = stringResource(R.string.lab_identity_optout_confirm),
            onConfirm = {
                controls.setOptOut(true)
                optedOut = true
                confirming = false
            },
            onDismiss = { confirming = false },
        )
    }
}
