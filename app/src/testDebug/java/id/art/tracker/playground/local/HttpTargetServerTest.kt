package id.art.tracker.playground.local

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTargetServerTest {
    private val server = HttpTargetServer(requestedPort = 0)

    @After
    fun stop() = server.shutdown()

    private fun get(path: String): Int {
        val conn = URL(server.baseUrl + path).openConnection() as HttpURLConnection
        val code = conn.responseCode
        conn.disconnect()
        return code
    }

    @Test
    fun statusPathsReturnThatStatus() {
        assertTrue(server.start())
        assertEquals(404, get("/status/404"))
        assertEquals(500, get("/status/500"))
        assertEquals(200, get("/status/200"))
    }

    @Test
    fun delayPathHoldsTheResponseForThatManySeconds() {
        assertTrue(server.start())
        val started = System.nanoTime()
        assertEquals(200, get("/delay/1"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("elapsed=$elapsedMs", elapsedMs >= 1_000)
    }

    @Test
    fun unknownOrMalformedPathsAre404() {
        assertTrue(server.start())
        assertEquals(404, get("/nope"))
        assertEquals(404, get("/status/abc"))
        assertEquals(404, get("/status/99999"))
        assertEquals(404, get("/delay/-1"))
    }

    @Test
    fun bindsToLoopbackOnly() {
        assertTrue(server.start())
        val address = server.boundAddress
        assertNotNull(address)
        assertTrue(address!!.isLoopbackAddress)
    }

    @Test
    fun startReturnsFalseWhenThePortIsBusy() {
        ServerSocket(0, 1, InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))).use { held ->
            val busy = HttpTargetServer(requestedPort = held.localPort)
            assertFalse(busy.start())
            busy.shutdown()
        }
    }
}
