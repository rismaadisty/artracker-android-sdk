package id.art.tracker.transport

import id.art.tracker.core.SdkLog
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** A key the HTTP stack refuses must never be echoed into the log, not even in debug builds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IngestClientKeyLogTest {
    @After
    fun tearDown() {
        SdkLog.enabled = false
    }

    @Test
    fun keyWithLineBreak_dropsTheBatchBeforeAnyConnection_andNeverLogsTheKey() {
        val server = MockWebServer().apply { start() }
        try {
            SdkLog.enabled = true
            ShadowLog.clear()
            val key = "art_live_SECRETSECRETSECRET\r\nX-Injected: 1"
            val result = IngestClient(server.url("/").toString().trimEnd('/'), key).send(byteArrayOf(1, 2, 3))

            assertEquals(SendResult.Drop(0), result)
            assertEquals("nothing may reach the network", 0, server.requestCount)
            val logs = ShadowLog.getLogs()
            assertTrue("a warning is expected", logs.any { it.msg.contains("control characters") })
            for (entry in logs) {
                val text = entry.msg + " " + entry.throwable?.toString() + " " + entry.throwable?.message
                assertTrue("key leaked in: $text", !text.contains("SECRETSECRET") && !text.contains("X-Injected"))
                assertTrue("a throwable would carry the header value", entry.throwable == null)
            }
        } finally {
            server.shutdown()
        }
    }
}
