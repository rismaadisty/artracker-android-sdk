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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.art.tracker.R
import id.art.tracker.playground.local.LocalServersProvider
import id.art.tracker.playground.settings.SharedPrefsSettingsStore
import id.art.tracker.playground.ui.ActionCard
import id.art.tracker.sample.SampleHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun expectationOf(t: HttpTarget): String = when {
    t.path.startsWith("/status/2") -> "item http_request level info"
    t.path.startsWith("/status/4") -> "item http_request level warn (4xx)"
    t.path.startsWith("/status/5") -> "item http_request level error (5xx)"
    t.path == "/delay/20" -> "melewati call timeout 3 s: item http_request level error"
    else -> "respons lambat 1 s: durasi terlihat di duration_ms"
}

/** HTTP calls through the SDK's OkHttp interceptor, against a loopback target server (no external network). */
@Composable
fun NetworkLab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SharedPrefsSettingsStore(context).load() }
    val base = LocalServersProvider.instance.targetBaseUrl
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf("-") }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(
                R.string.lab_network_effective, settings.networkSampleRate.toString(), settings.captureOnlyErrors,
                settings.ignoreHosts.joinToString().ifEmpty { "-" },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (base == null) {
            Text(stringResource(R.string.lab_network_unavailable), style = MaterialTheme.typography.bodyMedium)
        } else {
            HttpTargets.all.forEach { target ->
                ActionCard(target.label, expectationOf(target)) {
                    last = "memanggil…"
                    scope.launch { last = withContext(Dispatchers.IO) { SampleHttp.get(HttpTargets.urlOf(base, target)) } }
                    "memanggil ${target.label}"
                }
            }
        }
        Text(stringResource(R.string.lab_network_last, last), style = MaterialTheme.typography.bodyMedium)
    }
}
