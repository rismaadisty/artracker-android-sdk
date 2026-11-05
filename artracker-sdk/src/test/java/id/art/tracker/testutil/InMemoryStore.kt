package id.art.tracker.testutil

import id.art.tracker.core.KeyValueStore

internal class InMemoryStore : KeyValueStore {
    private val strings = HashMap<String, String>()
    private val longs = HashMap<String, Long>()

    override fun getString(k: String): String? = strings[k]

    override fun putString(k: String, v: String?) {
        if (v == null) strings.remove(k) else strings[k] = v
    }

    override fun getLong(k: String, def: Long): Long = longs[k] ?: def

    override fun putLong(k: String, v: Long) {
        longs[k] = v
    }
}
