package id.art.tracker.playground.status

import android.content.Context
import id.art.tracker.ARTracker
import id.art.tracker.playground.settings.PlaygroundSettings

/** Persisted copy of the opt-out choice (the SDK persists it too, but does not expose it). */
interface ControlStore {
    var optedOut: Boolean
}

class SharedPrefsControlStore(context: Context) : ControlStore {
    private val prefs = context.applicationContext.getSharedPreferences("playground_controls", Context.MODE_PRIVATE)

    override var optedOut: Boolean
        get() = prefs.getBoolean("optedOut", false)
        set(value) {
            prefs.edit().putBoolean("optedOut", value).commit()
        }
}

/** The three SDK switches the playground drives; a seam so the controls can be tested without the SDK. */
interface SdkSwitches {
    fun optOut(e: Boolean)
    fun startReplay()
    fun stopReplay()

    object Real : SdkSwitches {
        override fun optOut(e: Boolean) = ARTracker.optOut(e)
        override fun startReplay() = ARTracker.startReplay()
        override fun stopReplay() = ARTracker.stopReplay()
    }
}

/**
 * What the user last asked for. Opt-out persists across launches (so does the SDK's). The replay intent is in memory
 * only and restarts from `settings.replayEnabled`, because the SDK's `stopReplay` also only lasts for the process.
 */
class SdkControls(
    private val store: ControlStore,
    settings: PlaygroundSettings,
    private val sdk: SdkSwitches = SdkSwitches.Real,
) {
    val optedOut: Boolean get() = store.optedOut

    @Volatile
    var replayOn: Boolean = settings.replayEnabled
        private set

    fun setOptOut(enabled: Boolean) {
        sdk.optOut(enabled)
        store.optedOut = enabled
    }

    fun setReplay(on: Boolean) {
        if (on) sdk.startReplay() else sdk.stopReplay()
        replayOn = on
    }
}
