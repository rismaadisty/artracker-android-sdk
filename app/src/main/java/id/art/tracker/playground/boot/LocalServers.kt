package id.art.tracker.playground.boot

import java.io.File

/**
 * Debug-only loopback servers (MockWebServer): the sink the SDK posts to, and the target server the Network lab calls.
 * Release builds get a stub where nothing is available (see `LocalServersProvider` in `src/debug` and `src/release`).
 */
interface LocalServers {
    /** True when this build can host the sink at all (debug builds). */
    val sinkAvailable: Boolean

    /** Starts the sink on loopback; false if it cannot (port busy). Idempotent, never throws. */
    fun startSink(): Boolean

    /** Starts the Network lab's target server on loopback; false if it cannot. Idempotent, never throws. */
    fun startTargets(): Boolean

    val targetBaseUrl: String?

    /**
     * Whether the sink accepts screenshot chunks; false and ignored on builds without the sink. Turning it off makes
     * the sink answer `403 screenshot_disabled` to the `chunk-url` request of a chunk, which stops the SDK's recording.
     */
    var screenshotAllowed: Boolean

    /** One line about the screenshot chunks the sink received so far; null when unavailable. */
    fun screenshotSummary(): String?

    /** Writes the last screenshot frames the sink received (masked JPEGs, as sent) to [dir]; null when unavailable. */
    fun exportScreenshots(dir: File): List<File>?
}
