package id.art.tracker

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import id.art.tracker.playground.ui.PlaygroundHost
import id.art.tracker.playground.ui.PlaygroundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlaygroundTheme { PlaygroundHost() }
        }
        crashIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        crashIfAsked(intent)
    }

    /**
     * Debug builds only: `adb shell am start --activity-single-top -n id.art.tracker/.MainActivity --ez artracker.crash_now true` crashes the
     * main thread like the Crash lab's button, so scripts/e2e-crash-offline.sh can run without tapping through a dialog. The
     * single-top flag matters when the app is already on top: without it Android drops an intent that equals the launcher's
     * (extras are ignored by `filterEquals`) and `onNewIntent` never runs.
     */
    private fun crashIfAsked(intent: Intent?) {
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("artracker.crash_now", false) == true) {
            Handler(Looper.getMainLooper()).post { throw RuntimeException("Sample crash (artracker.crash_now)") }
        }
    }
}
