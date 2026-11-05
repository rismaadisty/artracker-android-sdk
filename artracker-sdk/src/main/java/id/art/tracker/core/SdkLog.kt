package id.art.tracker.core

import android.util.Log

internal object SdkLog {
    private const val TAG: String = "ARTracker"

    @Volatile
    var enabled: Boolean = false

    fun d(msg: String) {
        if (!enabled) return
        runCatching { Log.d(TAG, msg) }
    }

    fun w(msg: String, t: Throwable? = null) {
        if (!enabled) return
        runCatching {
            if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)
        }
    }
}
