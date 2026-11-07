package id.art.tracker

import android.app.Application
import id.art.tracker.playground.local.HttpTargetServer
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
        targets = HttpTargetServer(requestedPort = 0).also { check(it.start()) { "target server could not start" } }
        SampleApp.initTracker(
            this, ingest.url, EndpointGuard.DUMMY_KEY, debug = true,
            settings = PlaygroundSettings.DEFAULT.copy(flushIntervalMs = 1_000), beforeSend = null,
        )
    }

    companion object {
        lateinit var ingest: MockIngest
        lateinit var targets: HttpTargetServer
    }
}
