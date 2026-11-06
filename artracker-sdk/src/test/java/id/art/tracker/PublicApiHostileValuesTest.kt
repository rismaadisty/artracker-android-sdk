package id.art.tracker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.SdkLog
import id.art.tracker.testutil.QueueReader
import id.art.tracker.testutil.StrictJson
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for hostile values found by [PublicApiFuzzTest]: each one is tracked, followed by a canary that
 * proves the pipeline is still alive, and everything persisted must be strict JSON.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicApiHostileValuesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        app.deleteDatabase("artracker.db")
        ARTracker.init(
            app,
            ARTrackerConfig(
                endpoint = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}",
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
            ),
        )
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        app.deleteDatabase("artracker.db")
        SdkLog.enabled = false
    }

    private fun persistedAfter(block: () -> Unit): List<JSONObject> {
        block()
        ARTracker.track("canary")
        runBlocking { withTimeout(30_000) { ARTrackerCore.instanceOrNull()!!.awaitIdle() } }
        val payloads = QueueReader.drain(app)
        payloads.forEach { assertNull("not strict JSON: $it", StrictJson.validate(it)) }
        val items = payloads.map { JSONObject(it) }
        assertTrue("pipeline died: the canary was never persisted", items.any { it.getString("name") == "canary" })
        return items
    }

    @Test
    fun toStringThatThrowsAnError_doesNotKillThePipeline() {
        val value = object {
            override fun toString(): String = throw AssertionError("boom")
        }
        persistedAfter { ARTracker.track("hostile", mapOf("v" to value)) }
    }

    @Test
    fun toStringThatOverflowsTheStack_doesNotKillThePipeline() {
        class Node {
            val next: Any = this
            override fun toString(): String = "Node(next=$next)"
        }
        persistedAfter { ARTracker.track("hostile", mapOf("v" to Node())) }
    }

    @Test
    fun numberSubclassPrintingNaN_isStoredAsValidJson() {
        val number = object : Number() {
            override fun toByte(): Byte = 0
            override fun toDouble(): Double = Double.NaN
            override fun toFloat(): Float = Float.NaN
            override fun toInt(): Int = 0
            override fun toLong(): Long = 0
            override fun toShort(): Short = 0
            override fun toString(): String = "NaN"
        }
        val items = persistedAfter { ARTracker.track("hostile", mapOf("v" to number, "ok" to 1)) }
        val attrs = items.first { it.getString("name") == "hostile" }.getJSONObject("attributes")
        assertEquals(1, attrs.getInt("ok"))
        assertEquals("NaN", attrs.getString("v"))
    }
}
