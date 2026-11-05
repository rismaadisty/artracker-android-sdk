package id.art.tracker.telemetry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.BeforeSend
import id.art.tracker.LogLevel
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureExceptionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
    private val originalDefault = Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        ARTracker.init(
            app,
            ARTrackerConfig(
                endpoint = "http://127.0.0.1:9",
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
                beforeSend = BeforeSend { it.also(seen::add) },
            ),
        )
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        Thread.setDefaultUncaughtExceptionHandler(originalDefault)
    }

    private fun settle(): List<TelemetryItem> {
        runBlocking { withTimeout(15_000) { ARTrackerCore.instanceOrNull()!!.awaitIdle() } }
        return seen.toList()
    }

    private fun sameSite(message: String) = IllegalStateException(message)

    @Test
    fun capturedException_carriesStackFingerprintBreadcrumbsAndScreen() {
        ARTracker.screen("Checkout")
        ARTracker.track("pay_clicked")
        ARTracker.log(LogLevel.WARN, "slow promo")
        ARTracker.captureException(IllegalStateException("bad", RuntimeException("root")), mapOf("where" to "test"))

        val ex = settle().single { it.name == "exception" }

        assertEquals(LogLevel.ERROR, ex.level)
        assertEquals("java.lang.IllegalStateException: bad", ex.message)
        val a = ex.attributes
        assertEquals("test", a["where"])
        assertEquals(true, a["handled"])
        assertEquals("Checkout", a["screen"])
        assertEquals("java.lang.IllegalStateException", a["exception_class"])
        assertTrue((a["stacktrace"] as String).contains("Caused by: java.lang.RuntimeException: root"))
        assertEquals(40, (a["fingerprint"] as String).length)
        @Suppress("UNCHECKED_CAST")
        val crumbs = a["breadcrumbs"] as List<Map<String, Any?>>
        assertEquals(listOf("screen", "event", "log"), crumbs.map { it["type"] })
        assertEquals(listOf("Checkout", "pay_clicked", "slow promo"), crumbs.map { it["message"] })
    }

    @Test
    fun capturedException_addsABreadcrumbForLaterErrors() {
        ARTracker.captureException(RuntimeException("first"))
        ARTracker.captureException(IllegalStateException("second"))

        val second = settle().filter { it.name == "exception" }.last()

        @Suppress("UNCHECKED_CAST")
        val crumbs = second.attributes["breadcrumbs"] as List<Map<String, Any?>>
        assertEquals("error", crumbs.single()["type"])
        assertEquals("java.lang.RuntimeException: first", crumbs.single()["message"])
    }

    @Test
    fun identicalErrors_areCappedAtTenPerSession() {
        repeat(15) { ARTracker.captureException(sameSite("failure #$it")) }
        ARTracker.captureException(RuntimeException("another kind"))

        val items = settle().filter { it.name == "exception" }

        assertEquals(10, items.count { it.message!!.startsWith("java.lang.IllegalStateException") })
        assertEquals(1, items.count { it.message!!.startsWith("java.lang.RuntimeException") })
        assertNotNull(items.first().attributes["fingerprint"])
    }

    @Test
    fun newSession_resetsTheCap() {
        repeat(11) { ARTracker.captureException(sameSite("a")) }
        ARTracker.reset() // new session
        ARTracker.captureException(sameSite("b"))

        assertEquals(11, settle().count { it.name == "exception" })
    }
}
