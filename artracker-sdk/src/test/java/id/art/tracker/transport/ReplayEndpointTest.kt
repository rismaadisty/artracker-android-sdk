package id.art.tracker.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEndpointTest {
    @Test
    fun rejectsSchemeDowngrade() {
        assertFalse(ReplayEndpoint.uploadUrlAcceptable("https://api.x", "http://api.x/u"))
        assertTrue(ReplayEndpoint.uploadUrlAcceptable("https://api.x", "https://r2.y/u"))
        assertTrue(ReplayEndpoint.uploadUrlAcceptable("http://10.0.2.2:8080", "http://10.0.2.2:8080/u"))
        assertFalse(ReplayEndpoint.uploadUrlAcceptable("https://api.x", "ftp://a/u"))
        assertFalse(ReplayEndpoint.uploadUrlAcceptable("https://api.x", "not a url"))
    }

    @Test
    fun url_joinsEndpointAndPath() {
        assertEquals(
            "https://api.x/base/api/v1/replay/chunk-url",
            ReplayEndpoint.url("https://api.x/base/", ReplayEndpoint.CHUNK_URL_PATH)?.toString(),
        )
        assertNull(ReplayEndpoint.url("ftp://api.x", ReplayEndpoint.CHUNK_URL_PATH))
        assertNull(ReplayEndpoint.url("not a url", ReplayEndpoint.CHUNK_URL_PATH))
    }
}
