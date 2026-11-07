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
import id.art.tracker.ARTracker
import id.art.tracker.playground.scenario.tapTarget

@Composable
fun ProductDetail(onGo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sepatu lari", style = MaterialTheme.typography.headlineSmall)
        Text("Rp 120.000")
        Button(
            onClick = {
                ARTracker.track("add_to_cart", mapOf("sku" to 1, "price" to 120_000))
                onGo(ShopRoutes.CART)
            },
            modifier = Modifier.tapTarget(ShopTargets.ADD_TO_CART),
        ) { Text("Tambah ke keranjang") }
    }
}
