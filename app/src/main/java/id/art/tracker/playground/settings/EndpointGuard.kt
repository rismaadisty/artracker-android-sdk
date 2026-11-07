package id.art.tracker.playground.settings

import java.net.URI

enum class EndpointProfile { SINK, OFFLINE, LOCAL, BACKEND }

enum class EndpointClass { SAFE_LOCAL, REMOTE }

/**
 * Decides whether an endpoint may receive traffic without a confirmation. Everything that is not provably local is
 * [EndpointClass.REMOTE] (fail closed): a malformed URL, a non-http(s) scheme, a missing host, a look-alike host.
 */
object EndpointGuard {
    const val SINK_PORT = 38457
    const val TARGET_PORT = 38458
    const val SINK_ENDPOINT = "http://localhost:$SINK_PORT"
    const val OFFLINE_ENDPOINT = "http://sink.playground.invalid"

    /** Not a secret: only used for the loopback sink and the black hole, never for a real backend. */
    val DUMMY_KEY: String = "art_live_" + "p".repeat(43)

    private val SAFE_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun classify(url: String): EndpointClass {
        val uri = runCatching { URI(url) }.getOrNull() ?: return EndpointClass.REMOTE
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return EndpointClass.REMOTE
        val host = uri.host?.lowercase() ?: return EndpointClass.REMOTE
        return if (host in SAFE_HOSTS || host.endsWith(".invalid")) EndpointClass.SAFE_LOCAL else EndpointClass.REMOTE
    }

    /** Host only (never credentials, port or query), for display. "?" when it cannot be parsed. */
    fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "?"
}
