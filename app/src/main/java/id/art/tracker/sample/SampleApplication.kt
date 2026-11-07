package id.art.tracker.sample

import android.app.Application
import id.art.tracker.BuildConfig
import id.art.tracker.playground.ProcessInfo
import id.art.tracker.playground.boot.BootState
import id.art.tracker.playground.boot.Bootstrap
import id.art.tracker.playground.boot.QueueWiper
import id.art.tracker.playground.boot.SharedPrefsBackendGrantStore
import id.art.tracker.playground.boot.SharedPrefsRunMarkerStore
import id.art.tracker.playground.isMainProcess
import id.art.tracker.playground.local.LocalServersProvider
import id.art.tracker.playground.settings.SharedPrefsSettingsStore

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The SDK does not support multi-process apps: the `:restart` process must never initialise it.
        if (!isMainProcess(ProcessInfo.currentProcessName(this), packageName)) return

        val settings = SharedPrefsSettingsStore(this).load()
        val sinkUp = LocalServersProvider.instance.startSink()
        val grant = SharedPrefsBackendGrantStore(this).consume(System.currentTimeMillis())
        val resolved = Bootstrap.resolve(settings, BuildConfig.ARTRACKER_ENDPOINT, BuildConfig.ARTRACKER_KEY, grant, sinkUp)

        // The SDK sends leftovers right after init: nothing queued locally may be there when a remote endpoint starts.
        val marker = SharedPrefsRunMarkerStore(this)
        if (Bootstrap.shouldWipe(resolved.badge.remote, marker.lastRunRemote)) {
            QueueWiper.wipe(getDatabasePath("artracker.db").parentFile ?: filesDir, filesDir)
        }
        marker.lastRunRemote = resolved.badge.remote

        BootState.resolved = resolved
        SampleApp.initTracker(this, resolved.endpoint, resolved.apiKey, BuildConfig.DEBUG, settings, beforeSend = null)
    }
}
