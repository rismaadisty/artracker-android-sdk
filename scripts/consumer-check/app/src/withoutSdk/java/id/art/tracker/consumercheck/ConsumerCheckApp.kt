package id.art.tracker.consumercheck

import android.app.Application

/** Baseline for scripts/measure-size.sh (-PsdkMode=none): same class, same lifecycle, no Artracker import. */
class ConsumerCheckApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
