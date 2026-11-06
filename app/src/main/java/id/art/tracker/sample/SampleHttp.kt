package id.art.tracker.sample

import id.art.tracker.telemetry.okhttp.ARTrackerOkHttpInterceptor
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** OkHttp client whose calls are recorded by ARTracker. The 3 s call timeout makes `/delay/20` time out. */
object SampleHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(ARTrackerOkHttpInterceptor())
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    /** Blocking GET; call it off the main thread. Returns a short human-readable outcome. */
    fun get(url: String): String = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { "HTTP ${it.code}" }
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message}"
    }
}
