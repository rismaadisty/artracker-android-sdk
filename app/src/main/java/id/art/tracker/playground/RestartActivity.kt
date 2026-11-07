package id.art.tracker.playground

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Runs in the `:restart` process (see the manifest) after the main process killed itself: starts the launcher activity in a
 * fresh task and exits its own process. `SampleApplication` skips the SDK in this process (multi-process is not supported).
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        exitProcess()
    }

    companion object {
        /** Test seam: the real one ends this (`:restart`) process; a test must not end the JVM. */
        @JvmStatic
        var exitProcess: () -> Unit = { Runtime.getRuntime().exit(0) }
    }
}
