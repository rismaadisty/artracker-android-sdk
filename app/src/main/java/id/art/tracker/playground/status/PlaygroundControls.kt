package id.art.tracker.playground.status

import android.content.Context
import id.art.tracker.playground.settings.SharedPrefsSettingsStore

/** The one [SdkControls] of the process, so the host and the Replay lab never disagree about the replay intent. */
object PlaygroundControls {
    @Volatile
    private var instance: SdkControls? = null

    fun get(context: Context): SdkControls = instance ?: synchronized(this) {
        instance ?: SdkControls(
            SharedPrefsControlStore(context),
            SharedPrefsSettingsStore(context).load(),
        ).also { instance = it }
    }
}
