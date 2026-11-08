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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.playground.scenario.ScenarioTextField
import id.art.tracker.playground.scenario.tapTarget

/** Three-step sign-up (name, email, password); the last step identifies a new user. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Daftar — langkah $step dari 3", style = MaterialTheme.typography.headlineSmall)
        when (step) {
            1 -> {
                ScenarioTextField(ShopFields.ONB_NAME, "Nama", Modifier.fillMaxWidth())
                Button(onClick = { step = 2 }, modifier = Modifier.tapTarget(ShopTargets.ONB_NEXT_1)) { Text("Lanjut") }
            }
            2 -> {
                ScenarioTextField(ShopFields.ONB_EMAIL, "Email", Modifier.fillMaxWidth())
                Button(onClick = { step = 3 }, modifier = Modifier.tapTarget(ShopTargets.ONB_NEXT_2)) { Text("Lanjut") }
            }
            else -> {
                ScenarioTextField(ShopFields.ONB_PASSWORD, "Password", Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        ARTracker.identify("persona-new", mapOf("plan" to "free"))
                        ARTracker.track("onboarding_completed")
                        onDone()
                    },
                    modifier = Modifier.tapTarget(ShopTargets.ONB_FINISH),
                ) { Text("Selesai") }
            }
        }
    }
}
