package id.art.tracker.playground.local

import android.app.Application
import android.content.Context
import android.content.Intent
import id.art.tracker.ARTrackerSender
import id.art.tracker.ExperimentalARTrackerApi

/** Release stub: no Chucker, so the SDK keeps its built-in transport. */
@OptIn(ExperimentalARTrackerApi::class)
object NetworkInspector {
    @Suppress("UNUSED_PARAMETER")
    fun sender(app: Application): ARTrackerSender? = null

    @Suppress("UNUSED_PARAMETER")
    fun launchIntent(context: Context): Intent? = null
}
