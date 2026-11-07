package id.art.tracker.sample

import android.app.Application
import android.util.Log
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.BeforeSend
import id.art.tracker.ReplayConfig
import id.art.tracker.playground.settings.PlaygroundSettings

/** Sample-app glue around the SDK, kept free of `BuildConfig` so it can be unit tested. */
object SampleApp {
    private const val TAG = "ARTrackerSample"

    /**
     * Initialises ARTracker with demo settings (replay on, every session sampled; SDK debug logging only when
     * [debug] is true, i.e. `BuildConfig.DEBUG` in the real app).
     * With a blank [key] it logs once and skips the SDK, so the app still runs without a backend.
     * [settings] (null = the legacy fixed demo config) and [beforeSend] are optional; when [settings] is given,
     * [endpoint] must already be the resolved one (see `Bootstrap`).
     * Returns true when [ARTracker.init] was called.
     */
    fun initTracker(
        app: Application,
        endpoint: String,
        key: String,
        debug: Boolean,
        settings: PlaygroundSettings? = null,
        beforeSend: BeforeSend? = null,
    ): Boolean {
        if (key.isBlank()) {
            Log.w(TAG, "artracker.key is not set in local.properties; ARTracker is not initialised")
            return false
        }
        ARTracker.init(app, config(endpoint, key, debug, settings, beforeSend))
        return true
    }

    /** The demo configuration; `debug` is passed through, never hard-coded. */
    fun config(
        endpoint: String,
        key: String,
        debug: Boolean,
        settings: PlaygroundSettings? = null,
        beforeSend: BeforeSend? = null,
    ): ARTrackerConfig = settings?.toConfig(endpoint, key, debug, beforeSend) ?: ARTrackerConfig(
        endpoint = endpoint,
        apiKey = key,
        environment = "sample",
        debug = debug,
        replay = ReplayConfig(enabled = true, sampleRate = 1.0, framesPerSecond = 1.0),
        beforeSend = beforeSend,
    )
}
