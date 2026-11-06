package id.art.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.art.tracker.sample.legacy.LegacyActivity
import id.art.tracker.sample.screens.DiagnosticsScreen
import id.art.tracker.sample.screens.FeedScreen
import id.art.tracker.sample.screens.LoginScreen
import id.art.tracker.sample.screens.PaymentScreen
import id.art.tracker.ui.theme.ArtrackerandroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArtrackerandroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SampleNavHost(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun SampleNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    // Records a screen_view for every destination change (no-op until ARTracker.init succeeded).
    LaunchedEffect(navController) { ARTracker.trackNavController(navController) }
    NavHost(navController, startDestination = "home", modifier = modifier) {
        composable("home") { HomeScreen(onNavigate = { navController.navigate(it) }) }
        composable("login") { LoginScreen() }
        composable("payment") { PaymentScreen() }
        composable("feed") { FeedScreen() }
        composable("diagnostics") { DiagnosticsScreen() }
    }
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("ARTracker sample")
        val items = listOf("Login" to "login", "Payment" to "payment", "Feed" to "feed", "Diagnostics" to "diagnostics")
        items.forEach { (label, route) ->
            Button(onClick = { onNavigate(route) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(label)
            }
        }
        Button(
            onClick = { context.startActivity(Intent(context, LegacyActivity::class.java)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("Legacy XML screen") }
    }
}
