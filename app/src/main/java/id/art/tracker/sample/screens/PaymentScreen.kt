package id.art.tracker.sample.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.privacy.artrackerMask

/** Card fields plus a total wrapped in `Modifier.artrackerMask()` (replay shows it as asterisks). */
@Composable
fun PaymentScreen(modifier: Modifier = Modifier) {
    var card by rememberSaveable { mutableStateOf("") }
    var cvv by rememberSaveable { mutableStateOf("") }
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Payment")
        OutlinedTextField(
            value = card,
            onValueChange = { card = it },
            label = { Text("Card number") },
            keyboardOptions = numbers,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = cvv,
            onValueChange = { cvv = it },
            label = { Text("CVV") },
            keyboardOptions = numbers,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text("Total: Rp 120.000", modifier = Modifier.padding(top = 16.dp).artrackerMask())
        Button(
            onClick = { ARTracker.track("checkout_clicked", mapOf("total" to 120000)) },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Pay now") }
    }
}
