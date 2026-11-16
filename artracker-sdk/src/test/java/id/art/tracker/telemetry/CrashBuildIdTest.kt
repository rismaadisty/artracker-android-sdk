package id.art.tracker.telemetry

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.testutil.FakeClock
import id.art.tracker.transport.ReplayStore
import id.art.tracker.transport.TransportConfigStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The build id on a JVM crash item: only ever a volatile read, never a wait for the PackageManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashBuildIdTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var core: ARTrackerCore? = null

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        clean()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        clean()
    }

    private fun clean() {
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
        app.getSharedPreferences(TransportConfigStore.PREFS_NAME, 0).edit().clear().commit()
    }

    private fun startCore(reader: (Context) -> String?): ARTrackerCore {
        val c = ARTrackerCore(
            app,
            ARTrackerConfig(
                endpoint = "http://127.0.0.1:9",
                apiKey = "art_live_" + "a".repeat(43),
                environment = "test",
                appVersion = "1.0",
            ),
            FakeClock(1_700_000_000_000L),
            buildIdReader = reader,
        )
        ARTrackerCore.install(c)
        c.start()
        core = c
        return c
    }

    private fun settle() = runBlocking { withTimeout(15_000) { core!!.awaitIdle() } }

    private fun attributes(c: ARTrackerCore): JSONObject =
        JSONObject(c.crashItemJsonForTest(Thread.currentThread(), RuntimeException("boom"))).getJSONObject("attributes")

    @Test
    fun crashCarriesTheBuildIdOnceRead() {
        val c = startCore { "git-abc" }
        settle()

        assertEquals("git-abc", attributes(c).getString("build_id"))
    }

    @Test
    fun crashWhileTheBuildIdIsBeingReadDoesNotWait() {
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val c = startCore {
                release.await(20, TimeUnit.SECONDS) // a slow PackageManager
                "git-abc"
            }
            // The crash handler runs on its own (dying) thread: it must finish at once, without the value.
            val json = executor.submit<JSONObject> { attributes(c) }.get(1, TimeUnit.SECONDS)

            assertFalse(json.has("build_id"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        settle()
    }

    @Test
    fun crashWithoutMetaDataHasNoBuildId() {
        val c = startCore { null }
        settle()

        assertFalse(attributes(c).has("build_id"))
    }
}
