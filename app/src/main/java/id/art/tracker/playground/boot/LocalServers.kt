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

    /** Writes the last replay chunks the sink received (masked, as sent) to [dir]; null when unavailable. */
    fun exportReplayChunks(dir: File): File?

    /**
     * What the sink answers to `GET /api/v1/replay/config` (`screenshot_enabled`); false and ignored on builds without
     * the sink. Turning it off also makes the sink answer `403 screenshot_disabled` to screenshot chunks.
     */
    var screenshotAllowed: Boolean

    /** One line about the screenshot chunks the sink received so far; null when unavailable. */
    fun screenshotSummary(): String?

    /** Writes the last screenshot frames the sink received (masked JPEGs, as sent) to [dir]; null when unavailable. */
    fun exportScreenshots(dir: File): List<File>?
}
