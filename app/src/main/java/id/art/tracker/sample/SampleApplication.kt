package id.art.tracker.sample

import android.app.Application
import id.art.tracker.BuildConfig

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SampleApp.initTracker(this, BuildConfig.ARTRACKER_ENDPOINT, BuildConfig.ARTRACKER_KEY)
    }
}
