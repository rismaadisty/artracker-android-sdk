package id.art.tracker.playground.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointGuardTest {
    @Test
    fun safeLocalHosts() {
        listOf(
            "http://localhost:38457",
            "http://LOCALHOST",
            "http://127.0.0.1:3000",
            "http://10.0.2.2:3000",
            "http://sink.playground.invalid",
            "http://a.b.invalid/path",
        ).forEach { assertEquals(it, EndpointClass.SAFE_LOCAL, EndpointGuard.classify(it)) }
    }

    @Test
    fun remoteHostsAndLookalikes() {
        listOf(
            "https://artracker.exlm.my.id/",
            "https://203.0.113.1",
            "http://localhost.evil.com",
            "http://10.0.2.2.evil.com",
            "http://localhost@evil.com",
            "http://evil.com#@localhost",
            "ftp://localhost",
            "localhost:3000",
            "",
            "not a url",
        ).forEach { assertEquals(it, EndpointClass.REMOTE, EndpointGuard.classify(it)) }
    }

    @Test
    fun constantsAreConsistent() {
        assertEquals("http://localhost:${EndpointGuard.SINK_PORT}", EndpointGuard.SINK_ENDPOINT)
        assertEquals(EndpointClass.SAFE_LOCAL, EndpointGuard.classify(EndpointGuard.SINK_ENDPOINT))
        assertEquals(EndpointClass.SAFE_LOCAL, EndpointGuard.classify(EndpointGuard.OFFLINE_ENDPOINT))
        assertTrue(EndpointGuard.DUMMY_KEY.startsWith("art_live_"))
        assertTrue(EndpointGuard.DUMMY_KEY.length in 32..128)
        val config = PlaygroundSettings.DEFAULT.toConfig(EndpointGuard.SINK_ENDPOINT, EndpointGuard.DUMMY_KEY, false)
        assertEquals(EndpointGuard.SINK_ENDPOINT, config.endpoint)
        assertEquals(EndpointGuard.DUMMY_KEY, config.apiKey)
    }

    @Test
    fun hostOfReturnsOnlyTheHost() {
        assertEquals("h.example", EndpointGuard.hostOf("https://u:p@h.example:8443/x?k=v"))
        assertEquals("?", EndpointGuard.hostOf("bad"))
    }
}
