package id.art.tracker.playground.boot

import id.art.tracker.playground.settings.EndpointClass
import id.art.tracker.playground.settings.EndpointGuard
import id.art.tracker.playground.settings.EndpointProfile
import id.art.tracker.playground.settings.PlaygroundSettings

/** What the status strip shows about where events go. [remote] is true only for a REMOTE endpoint in use. */
data class EndpointBadge(val profile: EndpointProfile, val host: String, val remote: Boolean)

/** The endpoint and key the SDK is started with, and why a requested profile was not honoured (if it was not). */
data class Resolved(val endpoint: String, val apiKey: String, val badge: EndpointBadge, val notice: String?)

/** In-memory result of this process' boot, read by the UI. */
object BootState {
    @Volatile
    var resolved: Resolved? = null
}

object Bootstrap {
    /**
     * Pure decision, fail closed. The real key (and a REMOTE endpoint) is only ever returned for LOCAL, for a BACKEND whose
     * endpoint is local, or for a BACKEND with a consumed grant and a non-blank key. Every other case ends on the sink
     * (or, when the sink is not available, the black hole) with the dummy key.
     */
    fun resolve(
        settings: PlaygroundSettings,
        buildEndpoint: String,
        buildKey: String,
        grantConsumed: Boolean,
        sinkAvailable: Boolean,
    ): Resolved {
        val realKey = buildKey.trim().takeIf { it.isNotEmpty() }
        return when (settings.endpointProfile) {
            EndpointProfile.SINK -> sink(sinkAvailable, null)
            EndpointProfile.OFFLINE -> Resolved(
                EndpointGuard.OFFLINE_ENDPOINT, EndpointGuard.DUMMY_KEY,
                badge(EndpointProfile.OFFLINE, EndpointGuard.OFFLINE_ENDPOINT), null,
            )
            EndpointProfile.LOCAL ->
                if (EndpointGuard.classify(settings.localEndpoint) == EndpointClass.SAFE_LOCAL) {
                    Resolved(
                        settings.localEndpoint, realKey ?: EndpointGuard.DUMMY_KEY,
                        badge(EndpointProfile.LOCAL, settings.localEndpoint), null,
                    )
                } else {
                    sink(sinkAvailable, "Endpoint LOCAL bukan host lokal; memakai SINK")
                }
            EndpointProfile.BACKEND -> when {
                EndpointGuard.classify(buildEndpoint) == EndpointClass.SAFE_LOCAL -> Resolved(
                    buildEndpoint, realKey ?: EndpointGuard.DUMMY_KEY,
                    badge(EndpointProfile.BACKEND, buildEndpoint), null,
                )
                !grantConsumed -> sink(sinkAvailable, "Backend tidak diaktifkan: tidak ada konfirmasi yang masih berlaku; memakai SINK")
                realKey == null -> sink(sinkAvailable, "Backend tidak diaktifkan: kunci API belum diatur; memakai SINK")
                else -> Resolved(buildEndpoint, realKey, badge(EndpointProfile.BACKEND, buildEndpoint), null)
            }
        }
    }

    /** The queue is wiped before `init` only when this run moves from a non-remote to a remote endpoint. */
    fun shouldWipe(resolvedRemote: Boolean, lastRunRemote: Boolean): Boolean = resolvedRemote && !lastRunRemote

    private fun sink(sinkAvailable: Boolean, notice: String?): Resolved =
        if (sinkAvailable) {
            Resolved(EndpointGuard.SINK_ENDPOINT, EndpointGuard.DUMMY_KEY, badge(EndpointProfile.SINK, EndpointGuard.SINK_ENDPOINT), notice)
        } else {
            Resolved(
                EndpointGuard.OFFLINE_ENDPOINT, EndpointGuard.DUMMY_KEY,
                badge(EndpointProfile.OFFLINE, EndpointGuard.OFFLINE_ENDPOINT),
                listOfNotNull(notice, "Sink lokal tidak tersedia; memakai OFFLINE").joinToString("; "),
            )
        }

    private fun badge(profile: EndpointProfile, endpoint: String) = EndpointBadge(
        profile = profile,
        host = EndpointGuard.hostOf(endpoint),
        remote = EndpointGuard.classify(endpoint) == EndpointClass.REMOTE,
    )
}
