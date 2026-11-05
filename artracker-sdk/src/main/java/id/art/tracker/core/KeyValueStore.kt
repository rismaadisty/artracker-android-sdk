package id.art.tracker.core

import android.content.Context

internal interface KeyValueStore {
    fun getString(k: String): String?

    fun putString(k: String, v: String?)

    fun getLong(k: String, def: Long): Long

    fun putLong(k: String, v: Long)
}

/**
 * SharedPreferences-backed store. Keys in [SYNC_KEYS] (the kill-switch state) are written with
 * `commit()` so a crash right after tripping the switch cannot lose it; everything else uses `apply()`.
 */
internal class SharedPrefsStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(k: String): String? = prefs.getString(k, null)

    override fun putString(k: String, v: String?) {
        val e = prefs.edit()
        if (v == null) e.remove(k) else e.putString(k, v)
        save(e, k)
    }

    override fun getLong(k: String, def: Long): Long = prefs.getLong(k, def)

    override fun putLong(k: String, v: Long) {
        save(prefs.edit().putLong(k, v), k)
    }

    private fun save(e: android.content.SharedPreferences.Editor, key: String) {
        if (key in SYNC_KEYS) e.commit() else e.apply()
    }

    companion object {
        val SYNC_KEYS: Set<String> = setOf(KillSwitch.KEY_DISABLED_UNTIL, KillSwitch.KEY_OPTED_OUT)
    }
}
