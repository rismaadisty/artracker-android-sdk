package id.art.tracker.playground

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/** The SDK does not support multi-process apps: `init` must only run in the process named after the package. */
fun isMainProcess(processName: String?, packageName: String): Boolean = processName != null && processName == packageName

object ProcessInfo {
    /** Name of the current process, or null when it cannot be determined (callers must then treat it as not main). */
    fun currentProcessName(app: Application): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val pid = Process.myPid()
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return runCatching { am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName }.getOrNull()
    }
}
