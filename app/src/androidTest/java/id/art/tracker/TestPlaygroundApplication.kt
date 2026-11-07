package id.art.tracker

import android.app.Application
import id.art.tracker.playground.local.LocalServersProvider
import id.art.tracker.playground.scenario.ActivityHolder
import id.art.tracker.playground.settings.EndpointGuard
import id.art.tracker.playground.settings.PlaygroundSettings
import id.art.tracker.sample.SampleApp

/**
 * Starts the loopback servers, then the SDK against them. It never calls Bootstrap, so no instrumented test can use the
 * real key or a remote endpoint. The SDK is init-once per process, so every test class shares this one init and the
 * tests clear [ingest] in `@Before`.
 */
class TestPlaygroundApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ActivityHolder)
        ingest = MockIngest().also { it.start() }
        // The provider's server, because the scenarios' HTTP step reads LocalServersProvider.instance.targetBaseUrl.
        check(LocalServersProvider.instance.startTargets()) { "target server could not start (port busy?)" }
        SampleApp.initTracker(
            this, ingest.url, EndpointGuard.DUMMY_KEY, debug = true,
            settings = PlaygroundSettings.DEFAULT.copy(flushIntervalMs = 1_000), beforeSend = null,
        )
    }

    companion object {
        lateinit var ingest: MockIngest
        val targetsUrl: String get() = checkNotNull(LocalServersProvider.instance.targetBaseUrl) { "target server not started" }
    }
}
