package id.art.tracker.playground

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Runs in the `:restart` process (see the manifest): kills the main process whose pid it was given and starts the
 * launcher activity again. `SampleApplication` skips the SDK in this process (multi-process is not supported).
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        finish()
    }

    companion object {
        const val EXTRA_PID = "pid"
    }
}
