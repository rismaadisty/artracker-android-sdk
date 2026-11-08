package id.art.tracker.playground.local

import android.app.Application
import android.content.Context
import android.content.Intent
import com.chuckerteam.chucker.api.Chucker
import com.chuckerteam.chucker.api.ChuckerInterceptor
import id.art.tracker.ARTrackerSender
import id.art.tracker.ExperimentalARTrackerApi
import id.art.tracker.telemetry.okhttp.ARTrackerOkHttpSender
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Debug build: the SDK delivers its batches through an OkHttp client that has Chucker, so every ingest request shows
 * up in Chucker's "Network Activity" screen (notification, or long-press the app icon).
 *
 * This client must NOT get `ARTrackerOkHttpInterceptor`: the SDK's own requests are skipped by that interceptor, but a
 * second interceptor of ours would only add noise.
 */
@OptIn(ExperimentalARTrackerApi::class)
object NetworkInspector {
    fun sender(app: Application): ARTrackerSender {
        val chucker = ChuckerInterceptor.Builder(app)
            // The ingest key is a public key, but there is no reason to keep it in Chucker's database.
            .redactHeaders("X-Artracker-Key")
            .build()
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(chucker)
            .build()
        return ARTrackerOkHttpSender(client)
    }

    /** Opens Chucker's Network Activity screen. */
    fun launchIntent(context: Context): Intent? = Chucker.getLaunchIntent(context)
}
