package id.art.tracker.playground.inspector

import id.art.tracker.BeforeSend
import id.art.tracker.playground.settings.EndpointProfile

object Inspector {
    val ring = EventRing()

    /**
     * SINK shows everything through the sink (post-redaction), so it needs no hook; every other profile gets the
     * value-free [MetadataTap] only.
     */
    fun beforeSendFor(profile: EndpointProfile): BeforeSend? =
        if (profile == EndpointProfile.SINK) null else MetadataTap.beforeSend
}
