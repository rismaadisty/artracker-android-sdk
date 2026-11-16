package id.art.tracker.playground.scenario.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.art.tracker.playground.scenario.tapTarget

/** A button that does nothing, to be tapped repeatedly. Rapid taps here make the SDK flag the replay chunk as a rage click. */
@Composable
fun StuckScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Halaman macet", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { }, modifier = Modifier.tapTarget(ShopTargets.STUCK_BUTTON)) { Text("Kirim") }
    }
}
