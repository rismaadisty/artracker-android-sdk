package id.art.tracker.playground.settings

import id.art.tracker.ARTrackerConfig
import id.art.tracker.BeforeSend
import id.art.tracker.NetworkConfig
import id.art.tracker.PrivacyConfig
import id.art.tracker.ReplayConfig

/** Everything the playground lets the user change. The SDK config is immutable, so it takes effect after a restart. */
data class PlaygroundSettings(
    val endpointProfile: EndpointProfile = EndpointProfile.SINK,
    val localEndpoint: String = "http://10.0.2.2:3000",
    val environment: String = "sample",
    val replayEnabled: Boolean = true,
    val replaySampleRate: Double = 1.0,
    val replayFps: Double = 1.0,
    val maskAllInputs: Boolean = true,
    val maskAllText: Boolean = false,
    val networkSampleRate: Double = 1.0,
    val captureOnlyErrors: Boolean = false,
    val ignoreHosts: Set<String> = emptySet(),
    val flushIntervalMs: Long = 10_000,
    val sessionTimeoutMs: Long = 1_800_000,
) {
    /** The SDK's own rules (an invalid config leaves the SDK uninitialised) plus the LOCAL-must-be-local rule. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (flushIntervalMs < MIN_MS) errors += "flushIntervalMs minimal $MIN_MS ms"
        if (sessionTimeoutMs < MIN_MS) errors += "sessionTimeoutMs minimal $MIN_MS ms"
        if (!replayFps.isFinite()) errors += "replayFps harus berupa angka terhingga"
        if (!(replaySampleRate in 0.0..1.0)) errors += "replaySampleRate harus antara 0.0 dan 1.0"
        if (!(networkSampleRate in 0.0..1.0)) errors += "networkSampleRate harus antara 0.0 dan 1.0"
        if (endpointProfile == EndpointProfile.LOCAL && EndpointGuard.classify(localEndpoint) != EndpointClass.SAFE_LOCAL) {
            errors += "Endpoint LOCAL harus host lokal (localhost, 127.0.0.1, 10.0.2.2); host remote hanya lewat profil Backend"
        }
        return errors
    }

    /** BACKEND uses [backendEndpoint]; the other profiles ignore it. */
    fun endpointFor(backendEndpoint: String): String = when (endpointProfile) {
        EndpointProfile.SINK -> EndpointGuard.SINK_ENDPOINT
        EndpointProfile.OFFLINE -> EndpointGuard.OFFLINE_ENDPOINT
        EndpointProfile.LOCAL -> localEndpoint
        EndpointProfile.BACKEND -> backendEndpoint
    }

    fun isRemote(backendEndpoint: String): Boolean =
        EndpointGuard.classify(endpointFor(backendEndpoint)) == EndpointClass.REMOTE

    /** [endpoint] is already resolved by Bootstrap. */
    fun toConfig(endpoint: String, apiKey: String, debug: Boolean, beforeSend: BeforeSend? = null): ARTrackerConfig =
        ARTrackerConfig(
            endpoint = endpoint,
            apiKey = apiKey,
            environment = environment,
            debug = debug,
            replay = ReplayConfig(enabled = replayEnabled, sampleRate = replaySampleRate, framesPerSecond = replayFps),
            privacy = PrivacyConfig(maskAllInputs = maskAllInputs, maskAllText = maskAllText),
            network = NetworkConfig(
                ignoreHosts = ignoreHosts,
                sampleRate = networkSampleRate,
                captureOnlyErrors = captureOnlyErrors,
            ),
            flushIntervalMs = flushIntervalMs,
            sessionTimeoutMs = sessionTimeoutMs,
            beforeSend = beforeSend,
        )

    companion object {
        private const val MIN_MS = 1_000L
        val DEFAULT = PlaygroundSettings()
    }
}

/** A preset never changes the endpoint profile or the local endpoint. */
enum class SettingsPreset {
    DEFAULT, PRIVACY_STRICT, DEBUG_FAST, LOW_BANDWIDTH;

    fun apply(base: PlaygroundSettings): PlaygroundSettings = when (this) {
        DEFAULT -> PlaygroundSettings.DEFAULT.copy(endpointProfile = base.endpointProfile, localEndpoint = base.localEndpoint)
        PRIVACY_STRICT -> base.copy(maskAllInputs = true, maskAllText = true)
        DEBUG_FAST -> base.copy(flushIntervalMs = 1_000, sessionTimeoutMs = 10_000)
        LOW_BANDWIDTH -> base.copy(replaySampleRate = 0.1, captureOnlyErrors = true, flushIntervalMs = 30_000)
    }
}
