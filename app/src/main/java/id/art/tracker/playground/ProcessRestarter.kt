package id.art.tracker.playground

import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * Restarts the app the way ProcessPhoenix does: this (main) process starts [RestartActivity], which lives in its own
 * process, and then kills itself. By the time the `:restart` process is up the old process is gone, so the launcher
 * activity is created in a fresh process that runs `SampleApplication` (and reads the new settings) again.
 */
object ProcessRestarter {
    fun restart(context: Context, killSelf: () -> Unit = { Process.killProcess(Process.myPid()) }) {
        context.startActivity(Intent(context, RestartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        killSelf()
    }
}
