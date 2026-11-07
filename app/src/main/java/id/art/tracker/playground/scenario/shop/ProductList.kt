package id.art.tracker.playground.scenario.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.playground.scenario.tapTarget

private val products = listOf("Sepatu lari" to 120_000, "Tas ransel" to 250_000, "Topi" to 45_000)

@Composable
fun ProductList(onGo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Hasil pencarian", style = MaterialTheme.typography.headlineSmall)
        products.forEachIndexed { i, (name, price) ->
            val sku = i + 1
            val modifier = if (sku == 1) Modifier.tapTarget(ShopTargets.PRODUCT_1) else Modifier
            Card(
                modifier.fillMaxWidth().clickable {
                    ARTracker.track("product_selected", mapOf("sku" to sku))
                    onGo(ShopRoutes.PRODUCT)
                },
            ) { Text("$name — Rp $price", Modifier.padding(16.dp)) }
        }
    }
}
