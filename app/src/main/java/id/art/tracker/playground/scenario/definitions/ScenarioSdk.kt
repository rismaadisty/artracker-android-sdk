package id.art.tracker.playground.scenario.definitions

import id.art.tracker.ARTracker
import id.art.tracker.playground.local.LocalServersProvider
import id.art.tracker.sample.SampleHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The SDK calls scenario steps make, behind an interface so the tests can record them in order. */
interface SdkCalls {
    fun track(name: String, properties: Map<String, Any?> = emptyMap())
    fun screen(name: String)
    fun identify(userId: String, traits: Map<String, Any?> = emptyMap())
    fun reset()
    fun captureException(throwable: Throwable)
    fun flush()

    /** GET [path] on the local target server; null when it is not running (release build or busy port). */
    suspend fun httpGet(path: String): String?
}

object RealSdkCalls : SdkCalls {
    override fun track(name: String, properties: Map<String, Any?>) = ARTracker.track(name, properties)
    override fun screen(name: String) = ARTracker.screen(name)
    override fun identify(userId: String, traits: Map<String, Any?>) = ARTracker.identify(userId, traits)
    override fun reset() = ARTracker.reset()
    override fun captureException(throwable: Throwable) = ARTracker.captureException(throwable)
    override fun flush() = ARTracker.flush()
    override suspend fun httpGet(path: String): String? {
        val base = LocalServersProvider.instance.targetBaseUrl ?: return null
        return withContext(Dispatchers.IO) { SampleHttp.get(base.trimEnd('/') + path) }
    }
}

/** Test seam: tests replace [impl] with a recorder and restore [RealSdkCalls] afterwards. */
object ScenarioSdk {
    @Volatile
    var impl: SdkCalls = RealSdkCalls
}
