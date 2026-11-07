package id.art.tracker.playground.labs

data class HttpTarget(val label: String, val path: String)

/** Calls the Network lab can make; they go to the debug-only loopback target server, never to an external host. */
object HttpTargets {
    val all: List<HttpTarget> = listOf(
        HttpTarget("HTTP 200", "/status/200"),
        HttpTarget("HTTP 404", "/status/404"),
        HttpTarget("HTTP 500", "/status/500"),
        HttpTarget("Timeout (call timeout 3 s)", "/delay/20"),
        HttpTarget("Lambat (1 s)", "/delay/1"),
    )

    fun urlOf(base: String, target: HttpTarget): String = base.trimEnd('/') + target.path
}
