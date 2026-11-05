package id.art.tracker.transport

import id.art.tracker.core.KeyValueStore

/**
 * Persists what a freshly spawned process (a WorkManager-started [FlushWorker]) needs to send without `init()`:
 * endpoint, public API key and the envelope context values (ARCHITECTURE.md section 9.4).
 */
internal class TransportConfigStore(private val store: KeyValueStore) {
    internal data class Saved(
        val endpoint: String,
        val apiKey: String,
        val environment: String,
        val appVersion: String,
    )

    fun save(endpoint: String, apiKey: String, environment: String, appVersion: String) {
        store.putString(KEY_ENDPOINT, endpoint)
        store.putString(KEY_API_KEY, apiKey)
        store.putString(KEY_ENVIRONMENT, environment)
        store.putString(KEY_APP_VERSION, appVersion)
    }

    /** Returns the saved configuration, or null when nothing (or an unusable endpoint/key) was saved. */
    fun load(): Saved? {
        val endpoint = store.getString(KEY_ENDPOINT)?.takeIf { it.isNotBlank() } ?: return null
        val apiKey = store.getString(KEY_API_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return Saved(
            endpoint = endpoint,
            apiKey = apiKey,
            environment = store.getString(KEY_ENVIRONMENT) ?: "production",
            appVersion = store.getString(KEY_APP_VERSION) ?: "unknown",
        )
    }

    companion object {
        /**
         * SharedPreferences file for transport state. The [id.art.tracker.core.KillSwitch] must live in the same
         * file so the background worker honours opt-out and a tripped 401 switch even in a fresh process.
         */
        const val PREFS_NAME = "artracker_transport"

        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENVIRONMENT = "environment"
        private const val KEY_APP_VERSION = "app_version"
    }
}
