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

@Composable
fun Cart(onGo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Keranjang", style = MaterialTheme.typography.headlineSmall)
        Text("1 × Sepatu lari — Rp 120.000")
        Button(onClick = { onGo(ShopRoutes.CHECKOUT) }, modifier = Modifier.tapTarget(ShopTargets.GO_CHECKOUT)) { Text("Checkout") }
    }
}
