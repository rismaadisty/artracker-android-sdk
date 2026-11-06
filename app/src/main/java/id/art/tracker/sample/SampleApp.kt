package id.art.tracker.sample

import android.app.Application
import android.util.Log
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.ReplayConfig

/** Sample-app glue around the SDK, kept free of `BuildConfig` so it can be unit tested. */
object SampleApp {
    private const val TAG = "ARTrackerSample"

    /**
     * Initialises ARTracker with demo settings (debug logging, replay on, every session sampled).
     * With a blank [key] it logs once and skips the SDK, so the app still runs without a backend.
     * Returns true when [ARTracker.init] was called.
     */
    fun initTracker(app: Application, endpoint: String, key: String): Boolean {
        if (key.isBlank()) {
            Log.w(TAG, "artracker.key is not set in local.properties; ARTracker is not initialised")
            return false
        }
        ARTracker.init(
            app,
            ARTrackerConfig(
                endpoint = endpoint,
                apiKey = key,
                environment = "sample",
                debug = true,
                replay = ReplayConfig(enabled = true, sampleRate = 1.0, framesPerSecond = 1.0),
            ),
        )
        return true
    }
}
