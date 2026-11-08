package id.art.tracker.playground.scenario.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.playground.scenario.ScenarioTextField
import id.art.tracker.playground.scenario.tapTarget

@Composable
fun ShopHome(onGo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Toko Demo", style = MaterialTheme.typography.headlineSmall)
        ScenarioTextField(ShopFields.SEARCH, "Cari produk", Modifier.fillMaxWidth())
        Button(
            onClick = {
                ARTracker.track("search_submitted", mapOf("source" to "shop"))
                onGo(ShopRoutes.PRODUCTS)
            },
            modifier = Modifier.tapTarget(ShopTargets.SEARCH_GO),
        ) { Text("Cari") }
    }
}
