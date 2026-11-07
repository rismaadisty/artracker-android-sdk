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
}
