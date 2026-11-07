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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.R
import id.art.tracker.playground.boot.BootState
import id.art.tracker.playground.inspector.Inspector
import id.art.tracker.playground.settings.EndpointProfile
import id.art.tracker.playground.ui.ActionCard

/** Crash, background-thread crash, ANR and the 15-identical-errors dedupe demo. The destructive ones ask first. */
@Composable
fun CrashLab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val notes = remember { CrashNote(SharedPrefsNoteStore(context)) }
    val sink = BootState.resolved?.badge?.profile == EndpointProfile.SINK
    val items by Inspector.ring.items.collectAsState()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.lab_crash_note), style = MaterialTheme.typography.bodySmall)
        ActionCard(
            stringResource(R.string.lab_crash_main), stringResource(R.string.lab_crash_main_desc),
            destructive = true, confirmText = stringResource(R.string.lab_crash_confirm),
        ) {
            notes.recordBeforeCrash("main thread")
            throw RuntimeException("Sample crash")
        }
        ActionCard(
            stringResource(R.string.lab_crash_background), stringResource(R.string.lab_crash_background_desc),
            destructive = true, confirmText = stringResource(R.string.lab_crash_confirm),
        ) {
            notes.recordBeforeCrash("background thread")
            Thread { throw RuntimeException("Sample background crash") }.start()
            null
        }
        ActionCard(
            stringResource(R.string.lab_crash_anr), stringResource(R.string.lab_crash_anr_desc),
            destructive = true, confirmText = stringResource(R.string.lab_crash_confirm_anr),
        ) {
            notes.recordBeforeCrash("ANR")
            Thread.sleep(10_000)
            null
        }
        ActionCard(stringResource(R.string.lab_crash_dedupe), stringResource(R.string.lab_crash_dedupe_desc)) {
            // One call site on purpose: identical stack traces give identical fingerprints.
            repeat(15) { ARTracker.captureException(IllegalStateException("dedupe-demo"), mapOf("lab" to "crash")) }
            ARTracker.flush()
            "15 error dikirim; hitungan diterima ada di bawah"
        }
        Text(
            if (sink) {
                stringResource(R.string.lab_crash_counts, FingerprintCounter.countExceptions(items).toString())
            } else {
                stringResource(R.string.lab_crash_counts_unavailable)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.lab_crash_offline_guide), style = MaterialTheme.typography.bodySmall)
    }
}
