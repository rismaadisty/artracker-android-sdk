package id.art.tracker.playground.scenario.shop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PaymentResult() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pembayaran berhasil", style = MaterialTheme.typography.headlineSmall)
        Text("Terima kasih sudah berbelanja.")
    }
}
