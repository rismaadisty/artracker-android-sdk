package id.art.tracker

import android.view.View

/** Configuration for the ARTracker SDK. */
public class ARTrackerConfig @JvmOverloads constructor(
    public val endpoint: String,
    public val apiKey: String,
    public val environment: String = "production",
    public val appVersion: String? = null,
    public val debug: Boolean = false,
    public val replay: ReplayConfig = ReplayConfig(),
    public val privacy: PrivacyConfig = PrivacyConfig(),
    public val network: NetworkConfig = NetworkConfig(),
    public val flushIntervalMs: Long = 10_000,
    public val sessionTimeoutMs: Long = 1_800_000,
    public val beforeSend: BeforeSend? = null,
) {
    /** Fluent builder for Java callers. */
    public class Builder(private val endpoint: String, private val apiKey: String) {
        private var environment: String = "production"
        private var appVersion: String? = null
        private var debug: Boolean = false
        private var replay: ReplayConfig = ReplayConfig()
        private var privacy: PrivacyConfig = PrivacyConfig()
        private var network: NetworkConfig = NetworkConfig()
        private var flushIntervalMs: Long = 10_000
        private var sessionTimeoutMs: Long = 1_800_000
        private var beforeSend: BeforeSend? = null

        public fun environment(value: String): Builder = apply { environment = value }
        public fun appVersion(value: String?): Builder = apply { appVersion = value }
        public fun debug(value: Boolean): Builder = apply { debug = value }
        public fun replay(value: ReplayConfig): Builder = apply { replay = value }
        public fun privacy(value: PrivacyConfig): Builder = apply { privacy = value }
        public fun network(value: NetworkConfig): Builder = apply { network = value }
        public fun flushIntervalMs(value: Long): Builder = apply { flushIntervalMs = value }
        public fun sessionTimeoutMs(value: Long): Builder = apply { sessionTimeoutMs = value }
        public fun beforeSend(value: BeforeSend?): Builder = apply { beforeSend = value }

        public fun build(): ARTrackerConfig = ARTrackerConfig(
            endpoint = endpoint,
            apiKey = apiKey,
            environment = environment,
            appVersion = appVersion,
            debug = debug,
            replay = replay,
            privacy = privacy,
            network = network,
            flushIntervalMs = flushIntervalMs,
            sessionTimeoutMs = sessionTimeoutMs,
            beforeSend = beforeSend,
        )
    }
}

/** Size and JPEG quality of a replay screenshot frame: `LOW` 480 px / 50, `STANDARD` 720 px / 60, `HIGH` 1080 px / 75. */
public enum class ReplayQuality { LOW, STANDARD, HIGH }

/**
 * Session replay settings. A replay is a sequence of real screenshots of the app, masked on the device before they are
 * compressed (no pixel of a sensitive view leaves the device).
 *
 * [framesPerSecond] is clamped to 0.5..10 (2 is recommended). [quality] sets the size and JPEG quality of each frame.
 */
public class ReplayConfig @JvmOverloads constructor(
    public val enabled: Boolean = false,
    public val sampleRate: Double = 0.1,
    public val framesPerSecond: Double = 1.0,
    public val quality: ReplayQuality = ReplayQuality.STANDARD,
)

/** Privacy masking settings. */
public class PrivacyConfig @JvmOverloads constructor(
    public val maskAllInputs: Boolean = true,
    public val maskAllText: Boolean = false,
    public val sensitiveViewClasses: Set<Class<out View>> = emptySet(),
)

/**
 * Network settings: what `ARTrackerOkHttpInterceptor` captures, and how batches are delivered.
 *
 * [captureHeaders] is reserved: headers are never captured today, whatever its value.
 * [ignoreHosts] are matched exactly against the request host, ignoring case.
 * [sender] replaces the transport that posts batches to the ingest endpoint (null = the built-in `HttpURLConnection`
 * transport). See [ARTrackerSender].
 */
public class NetworkConfig @JvmOverloads constructor(
    public val captureHeaders: Boolean = false,
    public val ignoreHosts: Set<String> = emptySet(),
    public val sampleRate: Double = 1.0,
    public val captureOnlyErrors: Boolean = false,
    @property:ExperimentalARTrackerApi
    public val sender: ARTrackerSender? = null,
)

private const val KEY_PREFIX: String = "art_live_"
private const val KEY_MIN_LENGTH: Int = 32
private const val KEY_MAX_LENGTH: Int = 128
private const val MIN_FLUSH_INTERVAL_MS: Long = 1_000
private const val MIN_SESSION_TIMEOUT_MS: Long = 1_000
private const val DEFAULT_FPS: Double = 1.0
private const val MIN_FPS: Double = 0.5
private const val MAX_FPS: Double = 10.0

/** Returns human-readable validation errors; an empty list means the config is valid. */
internal fun ARTrackerConfig.validate(): List<String> {
    val errors = mutableListOf<String>()
    if (!(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
        errors += "endpoint must start with http:// or https://"
    }
    if (!apiKey.startsWith(KEY_PREFIX)) {
        errors += "apiKey must start with $KEY_PREFIX (secret keys must never be embedded in an app)"
    }
    if (apiKey.length < KEY_MIN_LENGTH || apiKey.length > KEY_MAX_LENGTH) {
        errors += "apiKey length must be between $KEY_MIN_LENGTH and $KEY_MAX_LENGTH characters"
    }
    // Never echo the key itself: the message ends up in logs. A CR/LF in a header value must not reach the HTTP stack.
    if (apiKey.any { it.code < 0x20 || it.code == 0x7F }) {
        errors += "apiKey must not contain control characters"
    }
    if (flushIntervalMs < MIN_FLUSH_INTERVAL_MS) {
        errors += "flushIntervalMs must be >= $MIN_FLUSH_INTERVAL_MS"
    }
    if (sessionTimeoutMs < MIN_SESSION_TIMEOUT_MS) {
        errors += "sessionTimeoutMs must be >= $MIN_SESSION_TIMEOUT_MS"
    }
    if (!replay.framesPerSecond.isFinite()) {
        errors += "replay.framesPerSecond must be a finite number"
    }
    if (!(replay.sampleRate in 0.0..1.0)) {
        errors += "replay.sampleRate must be between 0.0 and 1.0"
    }
    if (!(network.sampleRate in 0.0..1.0)) {
        errors += "network.sampleRate must be between 0.0 and 1.0"
    }
    return errors
}

/** Frames per second clamped to the supported range 0.5..10.0. A non-finite value (NaN, infinity) means the default 1.0. */
internal fun ReplayConfig.effectiveFps(): Double {
    if (!framesPerSecond.isFinite()) return DEFAULT_FPS
    return framesPerSecond.coerceIn(MIN_FPS, MAX_FPS)
}

/** Output size and JPEG quality of one [ReplayQuality]; the short side of the image is never upscaled. */
internal class QualityPreset(val shortSidePx: Int, val jpegQuality: Int)

internal fun ReplayQuality.preset(): QualityPreset = when (this) {
    ReplayQuality.LOW -> QualityPreset(480, 50)
    ReplayQuality.STANDARD -> QualityPreset(720, 60)
    ReplayQuality.HIGH -> QualityPreset(1080, 75)
}

/** The next preset down, or null at [ReplayQuality.LOW]. */
internal fun ReplayQuality.lower(): ReplayQuality? = when (this) {
    ReplayQuality.HIGH -> ReplayQuality.STANDARD
    ReplayQuality.STANDARD -> ReplayQuality.LOW
    ReplayQuality.LOW -> null
}
