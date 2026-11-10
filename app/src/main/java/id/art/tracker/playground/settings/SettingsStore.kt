package id.art.tracker.playground.settings

import android.content.Context
import id.art.tracker.ReplayQuality

/** [discarded] is true when something WAS saved but was corrupt or invalid, so [settings] is DEFAULT instead. */
data class LoadResult(val settings: PlaygroundSettings, val discarded: Boolean)

interface SettingsStore {
    /** Never throws and never returns invalid settings: corrupt or invalid saved state falls back to DEFAULT. */
    fun load(): PlaygroundSettings

    fun loadResult(): LoadResult = LoadResult(load(), discarded = false)
    fun save(settings: PlaygroundSettings)
}

class SharedPrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): PlaygroundSettings = loadResult().settings

    override fun loadResult(): LoadResult {
        val d = PlaygroundSettings.DEFAULT
        val loaded = runCatching {
            PlaygroundSettings(
                endpointProfile = prefs.getString("endpointProfile", null)?.let { EndpointProfile.valueOf(it) } ?: d.endpointProfile,
                localEndpoint = prefs.getString("localEndpoint", null) ?: d.localEndpoint,
                environment = prefs.getString("environment", null) ?: d.environment,
                replayEnabled = prefs.getBoolean("replayEnabled", d.replayEnabled),
                replaySampleRate = prefs.getString("replaySampleRate", null)?.toDouble() ?: d.replaySampleRate,
                replayFps = prefs.getString("replayFps", null)?.toDouble() ?: d.replayFps,
                replayQuality = prefs.getString("replayQuality", null)?.let { ReplayQuality.valueOf(it) } ?: d.replayQuality,
                maskAllInputs = prefs.getBoolean("maskAllInputs", d.maskAllInputs),
                maskAllText = prefs.getBoolean("maskAllText", d.maskAllText),
                networkSampleRate = prefs.getString("networkSampleRate", null)?.toDouble() ?: d.networkSampleRate,
                captureOnlyErrors = prefs.getBoolean("captureOnlyErrors", d.captureOnlyErrors),
                ignoreHosts = prefs.getStringSet("ignoreHosts", null)?.toSet() ?: d.ignoreHosts,
                flushIntervalMs = prefs.getLong("flushIntervalMs", d.flushIntervalMs),
                sessionTimeoutMs = prefs.getLong("sessionTimeoutMs", d.sessionTimeoutMs),
            )
        }.getOrNull()
        if (loaded != null && loaded.validate().isEmpty()) return LoadResult(loaded, discarded = false)
        // Nothing saved yet is not a discard; saved-but-unusable state is, and the caller should say so.
        return LoadResult(d, discarded = prefs.all.isNotEmpty())
    }

    /** `commit()` (synchronous): the caller restarts the process right after saving. */
    override fun save(settings: PlaygroundSettings) {
        prefs.edit()
            .putString("endpointProfile", settings.endpointProfile.name)
            .putString("localEndpoint", settings.localEndpoint)
            .putString("environment", settings.environment)
            .putBoolean("replayEnabled", settings.replayEnabled)
            .putString("replaySampleRate", settings.replaySampleRate.toString())
            .putString("replayFps", settings.replayFps.toString())
            .putString("replayQuality", settings.replayQuality.name)
            .putBoolean("maskAllInputs", settings.maskAllInputs)
            .putBoolean("maskAllText", settings.maskAllText)
            .putString("networkSampleRate", settings.networkSampleRate.toString())
            .putBoolean("captureOnlyErrors", settings.captureOnlyErrors)
            .putStringSet("ignoreHosts", settings.ignoreHosts)
            .putLong("flushIntervalMs", settings.flushIntervalMs)
            .putLong("sessionTimeoutMs", settings.sessionTimeoutMs)
            .commit()
    }

    private companion object {
        const val PREFS = "playground_settings"
    }
}
