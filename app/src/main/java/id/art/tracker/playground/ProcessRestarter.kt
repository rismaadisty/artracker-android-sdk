package id.art.tracker.playground

import android.content.Context
import android.content.Intent
import android.os.Process

/** Restarts the app: [RestartActivity] lives in its own process, kills this one and relaunches the launcher activity. */
object ProcessRestarter {
    fun restart(context: Context) {
        context.startActivity(
            Intent(context, RestartActivity::class.java)
                .putExtra(RestartActivity.EXTRA_PID, Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
