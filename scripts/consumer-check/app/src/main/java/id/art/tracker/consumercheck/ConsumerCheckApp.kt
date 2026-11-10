package id.art.tracker.consumercheck

import android.app.Application
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.ReplayConfig

/** Compiles against the published AAR; never runs. Mirrors the README quick start. */
class ConsumerCheckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ARTracker.init(
            this,
            ARTrackerConfig(
                endpoint = "https://ingest.example.com",
                apiKey = "art_live_00000000000000000000000000000000",
                environment = "check",
                appVersion = "1.0",
                replay = ReplayConfig(enabled = false),
            ),
        )
    }
}
