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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.R
import id.art.tracker.playground.ui.ActionCard

/** `identify` as one of three personas, and `reset` (logout: forgets the user, starts a new session). */
@Composable
fun IdentityLab(modifier: Modifier = Modifier) {
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
    }
}
