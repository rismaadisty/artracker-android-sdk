package id.art.tracker.playground.boot

import android.content.Context

/**
 * One-shot permission to use a REMOTE endpoint for the next process start. Issued by Settings after the user typed the
 * confirmation phrase; consumed (and cleared) at process start, so it can never be reused by a later launch.
 */
interface BackendGrantStore {
    fun issue(nowMs: Long)

    /** True only once, and only while `nowMs - issuedAt <= 120 000`. Always clears the grant. */
    fun consume(nowMs: Long): Boolean
}

/** Whether the previous process ran against a REMOTE endpoint (decides the pre-init queue wipe). */
interface RunMarkerStore {
    var lastRunRemote: Boolean
}

private const val BOOT_PREFS = "playground_boot"

/** `commit()` everywhere: the process is restarted or killed right after these writes. */
class SharedPrefsBackendGrantStore(context: Context) : BackendGrantStore {
    private val prefs = context.applicationContext.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)

    override fun issue(nowMs: Long) {
        prefs.edit().putLong(KEY_GRANT, nowMs).commit()
    }

    override fun consume(nowMs: Long): Boolean {
        val issuedAt = prefs.getLong(KEY_GRANT, NONE)
        prefs.edit().remove(KEY_GRANT).commit()
        return issuedAt != NONE && nowMs - issuedAt in 0..TTL_MS
    }

    private companion object {
        const val KEY_GRANT = "backendGrantIssuedAt"
        const val NONE = -1L
        const val TTL_MS = 120_000L
    }
}

class SharedPrefsRunMarkerStore(context: Context) : RunMarkerStore {
    private val prefs = context.applicationContext.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)

    override var lastRunRemote: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) {
            prefs.edit().putBoolean(KEY, value).commit()
        }

    private companion object {
        const val KEY = "lastRunRemote"
    }
}
