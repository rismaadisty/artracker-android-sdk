package id.art.tracker.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.Diagnostics
import id.art.tracker.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Buttons for every SDK feature plus a live `ARTracker.diagnostics()` panel. */
@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    var diagnostics by remember { mutableStateOf<Diagnostics?>(null) }
    var optedOut by remember { mutableStateOf(false) }
    var replaying by remember { mutableStateOf(false) }

    // diagnostics() reads the database: never on the main thread.
    LaunchedEffect(Unit) {
        while (isActive) {
            diagnostics = withContext(Dispatchers.IO) { ARTracker.diagnostics() }
            delay(1_000)
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Diagnostics: ${diagnostics ?: "loading"}")

        Button(onClick = { ARTracker.track("sample_event", mapOf("source" to "diagnostics")) }) { Text("Track event") }
        Button(onClick = { ARTracker.identify("sample-user-1", mapOf("plan" to "pro")) }) { Text("Identify") }
        Button(onClick = { ARTracker.log(LogLevel.WARN, "Sample warning", mapOf("screen" to "diagnostics")) }) {
            Text("Log WARN")
        }
        Button(onClick = { ARTracker.captureException(IllegalStateException("Sample handled error")) }) {
            Text("Capture exception")
        }
        Button(onClick = { throw RuntimeException("Sample crash") }) { Text("Crash now") }
        Button(onClick = { Thread.sleep(10_000) }) { Text("ANR 10 s") }
        Button(onClick = { ARTracker.flush() }) { Text("Flush") }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Opt out")
            Switch(checked = optedOut, onCheckedChange = {
                optedOut = it
                ARTracker.optOut(it)
            })
        }
        Button(onClick = {
            replaying = !replaying
            if (replaying) ARTracker.startReplay() else ARTracker.stopReplay()
        }) { Text(if (replaying) "Stop replay" else "Start replay") }
    }
}
