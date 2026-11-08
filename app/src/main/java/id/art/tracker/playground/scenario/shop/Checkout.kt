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
import id.art.tracker.privacy.artrackerMask

/** Email and card are ordinary fields (masked by maskAllInputs); the total is masked explicitly. */
@Composable
fun Checkout(onGo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pembayaran", style = MaterialTheme.typography.headlineSmall)
        ScenarioTextField(ShopFields.CHECKOUT_EMAIL, "Email", Modifier.fillMaxWidth())
        ScenarioTextField(ShopFields.CHECKOUT_CARD, "Nomor kartu", Modifier.fillMaxWidth())
        Text("Total: Rp 120.000", Modifier.artrackerMask())
        Button(
            onClick = {
                ARTracker.track("checkout_clicked", mapOf("total" to 120_000))
                onGo(ShopRoutes.RESULT)
            },
            modifier = Modifier.tapTarget(ShopTargets.PAY_NOW),
        ) { Text("Bayar sekarang") }
    }
}
