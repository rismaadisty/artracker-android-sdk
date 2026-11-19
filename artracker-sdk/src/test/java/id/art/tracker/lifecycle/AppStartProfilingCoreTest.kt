package id.art.tracker.lifecycle

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.AppStartConfig
import id.art.tracker.BeforeSend
import id.art.tracker.ExperimentalARTrackerApi
import id.art.tracker.NetworkConfig
import id.art.tracker.SendResponse
import id.art.tracker.TelemetryItem
import id.art.tracker.appstart.MainStackSource
import id.art.tracker.appstart.ProfileStore
import id.art.tracker.appstart.StartupProfiler
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.Clock
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.transport.ReplayStore
import id.art.tracker.transport.SendResult
import id.art.tracker.transport.Sender
import id.art.tracker.transport.TransportConfigStore
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The startup profiler wired into the core: which cold starts are profiled, and what is written and uploaded. */
@OptIn(ExperimentalARTrackerApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStartProfilingCoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
    private var now = 10_000L
    private val requests = ArrayList<Pair<Activity, () -> Unit>>()
    private val hook = FirstFrameHook { activity, onFrame ->
        requests += activity to onFrame
        {}
    }
    private val uploaded = CopyOnWriteArrayList<ByteArray>()
    private val ingestPosts = CopyOnWriteArrayList<String>()
    private var profilerFactoryCalls = 0
    private var profiler: StartupProfiler? = null
    private var core: ARTrackerCore? = null
    private val stack = arrayOf(StackTraceElement("com.example.Main", "onCreate", "Main.kt", 12))
    private fun profileDir() = File(app.filesDir, ProfileStore.DIR)

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
        app.getSharedPreferences(TransportConfigStore.PREFS_NAME, 0).edit().clear().commit()
        profileDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        profiler?.discard()
        profileDir().deleteRecursively()
        app.deleteDatabase("artracker.db")
        app.getSharedPreferences(TransportConfigStore.PREFS_NAME, 0).edit().clear().commit()
    }

    private fun config(appStart: AppStartConfig, sender: id.art.tracker.ARTrackerSender? = null) = ARTrackerConfig(
        endpoint = "http://127.0.0.1:9",
        apiKey = "art_live_" + "a".repeat(43),
        environment = "test",
        appVersion = "1.0",
        beforeSend = BeforeSend { it.also(seen::add) },
        network = NetworkConfig(sender = sender),
        appStart = appStart,
    )

    private fun startCore(
        appStart: AppStartConfig = AppStartConfig(profiling = true, profileSampleRate = 1.0),
        random: Double = 0.0,
        foreground: Boolean = true,
        profileSender: Sender = Sender { uploaded += it; SendResult.Accepted },
        ingestSender: id.art.tracker.ARTrackerSender? = null,
    ): ARTrackerCore {
        val c = ARTrackerCore(
            app, config(appStart, ingestSender), Clock.SYSTEM,
            appStartFactory = { onAppStart, onDone -> AppStartTracker(10_000L, foreground, { now }, hook, onAppStart, onDone) },
            warmHotFactory = { onAppStart -> WarmHotStartTracker({ now }, hook, onAppStart) },
            startInfoSource = StartInfoSource { it(null) },
            profilerFactory = {
                profilerFactoryCalls++
                StartupProfiler(MainStackSource { stack }, { now }, System::nanoTime, 10_000L).also { profiler = it }
            },
            profileRandom = { random },
            profileSender = profileSender,
        )
        ARTrackerCore.install(c)
        c.start()
        core = c
        return c
    }

    private fun settle(): List<TelemetryItem> {
        runBlocking { withTimeout(15_000) { core!!.awaitIdle() } }
        return seen.toList()
    }

    private fun List<TelemetryItem>.appStarts() = filter { it.name == "app_start" }

    private fun launch(): Activity = Robolectric.buildActivity(PlainActivity::class.java).setup().get()

    private fun waitForSamples() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && (profiler?.sampleCountForTest() ?: 0) == 0) Thread.sleep(5)
        assertTrue("the sampler took a sample", (profiler?.sampleCountForTest() ?: 0) > 0)
    }

    private fun samplerAlive(): Boolean =
        Thread.getAllStackTraces().keys.any { it.name == "artracker-startup-sampler" && it.isAlive }

    private fun assertSamplerEnds() {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && samplerAlive()) Thread.sleep(5)
        assertTrue("the sampler thread is not alive", !samplerAlive())
    }

    private fun gunzip(body: ByteArray) = GZIPInputStream(ByteArrayInputStream(body)).readBytes().toString(Charsets.UTF_8)

    private fun coldStartReported(frameAt: Long = 10_640) {
        now = 10_100
        launch()
        waitForSamples()
        now = frameAt
        requests.first().second()
    }

    @Test
    fun profiledColdStart_writesOneProfile_andMarksTheItem() {
        startCore()
        coldStartReported()

        val start = settle().appStarts().single()
        assertEquals(true, start.attributes["profiled"])
        assertEquals("one profile was uploaded", 1, uploaded.size)
        val root = JSONObject(gunzip(uploaded.single()))
        assertEquals(start.attributes["start_id"], root.getJSONObject("profile").getString("start_id"))
        assertEquals("test", root.getJSONObject("context").getString("environment"))
        assertEquals("1.0", root.getJSONObject("context").getString("app_version"))
        assertTrue(root.getJSONObject("profile").getJSONArray("samples").length() > 0)
        assertEquals("android", root.getJSONObject("profile").getString("platform"))
        assertTrue("an accepted profile leaves no file", ProfileStore(profileDir(), Clock.SYSTEM).pending().isEmpty())
        assertSamplerEnds()
    }

    @Test
    fun aProfileThatCannotBeUploaded_staysAsAFile() {
        startCore(profileSender = Sender { SendResult.NetworkError(java.io.IOException("offline")) })
        coldStartReported()

        settle()
        val pending = ProfileStore(profileDir(), Clock.SYSTEM).pending()
        assertEquals(1, pending.size)
        assertEquals(1, pending.single().attempts)
    }

    @Test
    fun notChosenByTheSampleRate_meansNoProfiler() {
        startCore(appStart = AppStartConfig(profiling = true, profileSampleRate = 0.1), random = 0.99)
        now = 10_100
        launch()
        now = 10_640
        requests.first().second()

        val start = settle().appStarts().single()
        assertEquals(false, start.attributes["profiled"])
        assertEquals(0, profilerFactoryCalls)
        assertTrue(uploaded.isEmpty())
        assertTrue(!samplerAlive())
    }

    @Test
    fun profilingOff_meansNoProfiler() {
        startCore(appStart = AppStartConfig(profiling = false, profileSampleRate = 1.0))
        now = 10_100
        launch()
        now = 10_640
        requests.first().second()

        assertEquals(false, settle().appStarts().single().attributes["profiled"])
        assertEquals(0, profilerFactoryCalls)
    }

    @Test
    fun aDroppedColdStart_discardsTheProfile() {
        startCore(foreground = false)
        now = 10_100
        launch()
        // the process was not started for the user: its first Activity is a warm start, no cold start, no profile
        now = 10_300
        requests.first().second()

        val start = settle().appStarts().single()
        assertEquals("warm", start.attributes["type"])
        assertEquals(false, start.attributes["profiled"])
        assertTrue(uploaded.isEmpty())
        assertTrue(!profileDir().exists() || profileDir().listFiles().isNullOrEmpty())
        assertSamplerEnds()
    }

    @Test
    fun backgroundBeforeTheFirstFrame_discardsTheProfile() {
        startCore()
        now = 10_100
        launch()
        waitForSamples()
        ProcessLifecycleTestUtil.start()
        ProcessLifecycleTestUtil.stop()
        now = 10_900
        requests.first().second()

        assertTrue(settle().appStarts().isEmpty())
        assertTrue(uploaded.isEmpty())
        assertTrue(!profileDir().exists() || profileDir().listFiles().isNullOrEmpty())
        assertSamplerEnds()
    }

    @Test
    fun disabledDuringStartup_discardsEverything() {
        // Review Focus 4: opt-out or the 401 kill switch while the app is starting.
        val c = startCore()
        now = 10_100
        launch()
        waitForSamples()
        c.optOut(true)
        assertSamplerEnds() // it stopped when the SDK was disabled, not at the first frame
        now = 10_500
        requests.first().second()

        assertTrue(settle().appStarts().isEmpty())
        assertTrue(uploaded.isEmpty())
        assertTrue(!profileDir().exists() || profileDir().listFiles().isNullOrEmpty())
    }

    @Test
    fun theKillSwitchTripDuringStartup_stopsTheSampler() {
        // A real 401 from the ingest endpoint trips the switch of the core's own flusher.
        val c = startCore(ingestSender = id.art.tracker.ARTrackerSender { SendResponse(401) })
        now = 10_100
        launch()
        waitForSamples()
        c.track("something", emptyMap())
        c.pipeline.flushNow()
        assertSamplerEnds()
        now = 10_500
        requests.first().second()

        assertTrue(settle().appStarts().isEmpty())
        assertTrue(uploaded.isEmpty())
    }

    @Test
    fun optOut_deletesPendingProfileFiles() {
        // Opting out discards what was recorded earlier; a stored profile must not be sent after opting back in.
        ProfileStore(profileDir(), Clock.SYSTEM).write("old", byteArrayOf(1, 2, 3))
        val c = startCore(appStart = AppStartConfig(profiling = false))

        c.optOut(true)
        settle()

        assertTrue(ProfileStore(profileDir(), Clock.SYSTEM).pending().isEmpty())
    }

    @Test
    fun appPackages_includeThePackageAndConfiguredExtras() {
        startCore(appStart = AppStartConfig(profiling = true, profileSampleRate = 1.0, inAppPackages = setOf("com.example.feature", app.packageName)))
        coldStartReported()
        settle()

        val packages = JSONObject(gunzip(uploaded.single())).getJSONObject("profile").getJSONArray("app_packages")
        assertEquals(listOf(app.packageName, "com.example.feature"), (0 until packages.length()).map { packages.getString(it) })
    }

    @Test
    fun goingToTheBackground_uploadsPendingProfiles() {
        ProfileStore(profileDir(), Clock.SYSTEM).write("older", byteArrayOf(9, 9, 9))
        startCore(appStart = AppStartConfig(profiling = false))
        ProcessLifecycleTestUtil.start()
        ProcessLifecycleTestUtil.stop()

        settle()
        assertEquals(1, uploaded.size)
        assertTrue(ProfileStore(profileDir(), Clock.SYSTEM).pending().isEmpty())
    }

    @Test
    fun aSlowProfileUpload_doesNotBlockIngestFlushing() {
        // Review Focus 3: the profile upload blocks on HTTP; it must run beside the ingest flush, not in front of it.
        val release = CountDownLatch(1)
        val ingestAnswered = CountDownLatch(1)
        val c = startCore(
            profileSender = Sender { release.await(10, TimeUnit.SECONDS); SendResult.Accepted },
            ingestSender = id.art.tracker.ARTrackerSender { request ->
                ingestPosts += request.url
                ingestAnswered.countDown()
                SendResponse(202)
            },
        )
        try {
            coldStartReported()
            c.track("something", emptyMap())
            c.pipeline.flushNow()

            assertTrue("ingest was delivered while the profile upload is stuck", ingestAnswered.await(8, TimeUnit.SECONDS))
            assertNotNull(ingestPosts.firstOrNull())
        } finally {
            release.countDown()
        }
        settle()
    }
}
