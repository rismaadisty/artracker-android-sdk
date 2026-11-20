package id.art.tracker.lifecycle

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.AppStartConfig
import id.art.tracker.BeforeSend
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.Clock
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SharedPrefsStore
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import id.art.tracker.transport.ReplayStore
import id.art.tracker.transport.TransportConfigStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `app_start` wired into the core: what reaches the pipeline, and when nothing does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStartCoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
    private var now = 10_000L
    private val requests = ArrayList<Pair<Activity, () -> Unit>>()
    private val hook = FirstFrameHook { activity, onFrame ->
        requests += activity to onFrame
        {}
    }
    private var core: ARTrackerCore? = null

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
        app.getSharedPreferences(TransportConfigStore.PREFS_NAME, 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        app.getSharedPreferences(TransportConfigStore.PREFS_NAME, 0).edit().clear().commit()
    }

    private fun config(appStart: AppStartConfig = AppStartConfig()) = ARTrackerConfig(
        endpoint = "http://127.0.0.1:9",
        apiKey = "art_live_" + "a".repeat(43),
        environment = "test",
        appVersion = "1.0",
        beforeSend = BeforeSend { it.also(seen::add) },
        appStart = appStart,
    )

    /** A core whose trackers use the fake hook and the test clock; null factory = the real ones. */
    private fun startCore(
        fake: Boolean = true,
        foreground: Boolean = true,
        startInfo: StartInfoSource? = null,
        appStart: AppStartConfig = AppStartConfig(),
        clock: Clock = Clock.SYSTEM,
    ): ARTrackerCore {
        val factory: (((AppStart) -> Unit, (ColdStartDrop?) -> Unit) -> AppStartTracker?)? = if (fake) {
            { onAppStart, onDone -> AppStartTracker(10_000L, foreground, { now }, hook, onAppStart, onDone) }
        } else {
            null
        }
        val warmHot: ((onAppStart: (AppStart) -> Unit) -> WarmHotStartTracker?)? = if (fake) {
            { onAppStart -> WarmHotStartTracker({ now }, hook, onAppStart) }
        } else {
            null
        }
        val c = ARTrackerCore(
            app, config(appStart), clock, appStartFactory = factory, warmHotFactory = warmHot,
            startInfoSource = startInfo ?: StartInfoSource { it(null) },
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

    @Test
    fun coldStart_emitsAPerfItemNamedAppStart() {
        startCore()
        ProcessLifecycleTestUtil.start()
        now = 10_100
        launch()
        now = 10_640
        requests.single().second()

        val items = settle()
        val start = items.appStarts().single()
        assertEquals(id.art.tracker.ItemType.PERF, start.type)
        assertEquals("app_start", start.name)
        assertEquals("cold", start.attributes["type"])
        assertEquals(640L, start.attributes["duration_ms"])
        assertEquals("PlainActivity", start.attributes["first_screen"])
        assertEquals(1, start.attributes["v"])
        assertEquals("android", start.attributes["platform"])
        assertEquals(false, start.attributes["profiled"])
        @Suppress("UNCHECKED_CAST")
        val phases = start.attributes["phases"] as List<Map<String, Any?>>
        assertEquals(640L, phases.sumOf { it["ms"] as Long })
        assertEquals(items.first { it.name == "session_start" }.sessionId, start.sessionId)
    }

    @Test
    fun noEventNamedAppStart_isEmittedAnyMore() {
        startCore()
        now = 10_100
        launch()
        now = 10_640
        requests.single().second()

        assertTrue(settle().none { it.type == id.art.tracker.ItemType.EVENT && it.name == "app_start" })
    }

    @Test
    fun itemTimestamp_isTheStartAnchor() {
        val wallNow = 1_700_000_000_000L
        startCore(clock = { wallNow })
        now = 10_100
        launch()
        now = 10_640
        requests.single().second()

        val start = settle().appStarts().single()
        val uptimeNow = android.os.SystemClock.uptimeMillis()
        assertEquals(wallNow - (uptimeNow - 10_000L), start.timestampMs)
    }

    @Test
    fun api35Timestamps_splitProcessToInit() {
        now = 10_400 // init: process_to_init is 400 ms
        startCore(startInfo = StartInfoSource { it(StartupTimestamps(forkMs = 0, bindApplicationMs = 120, applicationOnCreateMs = 250)) })
        now = 10_900
        launch()
        now = 11_000
        requests.single().second()

        @Suppress("UNCHECKED_CAST")
        val phases = settle().appStarts().single().attributes["phases"] as List<Map<String, Any?>>
        assertEquals(listOf("bind_application", "content_providers", "app_on_create_pre_init"), phases.take(3).map { it["name"] })
        assertEquals(listOf(120L, 130L, 150L), phases.take(3).map { it["ms"] })
        assertEquals(1_000L, phases.sumOf { it["ms"] as Long })
    }

    @Test
    fun startInfoNull_keepsProcessToInit() {
        now = 10_400
        startCore(startInfo = StartInfoSource { it(null) })
        now = 10_900
        launch()
        now = 11_000
        requests.single().second()

        @Suppress("UNCHECKED_CAST")
        val phases = settle().appStarts().single().attributes["phases"] as List<Map<String, Any?>>
        assertEquals("process_to_init", phases.first()["name"])
    }

    @Test
    fun aStartInfoThatNeverAnswers_isStillReportedByTheSourceItself() {
        // The source owns the 2 s timeout: it calls back with null. The core must emit on that callback, off the caller.
        var callback: ((StartupTimestamps?) -> Unit)? = null
        startCore(startInfo = StartInfoSource { callback = it })
        now = 10_100
        launch()
        now = 10_640
        requests.single().second()
        assertTrue("nothing is emitted before the source answers", settle().appStarts().isEmpty())

        callback!!.invoke(null)
        assertEquals(1, settle().appStarts().size)
    }

    @Test
    fun backgroundLaunchedProcess_reportsItsFirstActivityAsWarm() {
        startCore(foreground = false)
        now = 10_100
        launch()
        now = 10_300
        requests.single().second()

        val start = settle().appStarts().single()
        assertEquals("warm", start.attributes["type"])
        assertEquals("PlainActivity", start.attributes["first_screen"])
    }

    @Test
    fun returnFromBackground_reportsHot() {
        startCore()
        ProcessLifecycleTestUtil.start()
        now = 10_100
        val controller = Robolectric.buildActivity(PlainActivity::class.java).setup()
        now = 10_640
        requests.single().second() // the cold start

        controller.pause().stop()
        ProcessLifecycleTestUtil.stop()
        ProcessLifecycleTestUtil.start()
        now = 20_000
        controller.start()
        now = 20_050
        controller.resume()
        now = 20_120
        requests.last().second()

        val starts = settle().appStarts()
        assertEquals(listOf("cold", "hot"), starts.map { it.attributes["type"] })
        assertEquals(120L, starts.last().attributes["duration_ms"])
    }

    @Test
    fun lateInit_reportsNoWarmOrHotStartUntilTheAppWasInTheBackground() {
        // Review Focus 1: init ran with an Activity already alive; stopping and starting it again is not a start.
        val first = Robolectric.buildActivity(PlainActivity::class.java).setup()
        startCore()
        first.pause().stop()
        now = 10_200
        first.start().resume()
        requests.lastOrNull()?.second?.invoke()

        assertTrue(settle().appStarts().isEmpty())
    }

    @Test
    fun appStartDisabled_registersNoTracker() {
        val c = startCore(appStart = AppStartConfig(enabled = false))
        now = 10_100
        launch()
        requests.lastOrNull()?.second?.invoke()

        assertNull(c.appStartTracker)
        assertNull(c.warmHotTracker)
        assertTrue(requests.isEmpty())
        assertTrue(settle().appStarts().isEmpty())
    }

    @Test
    fun trackerUnregistersItselfWhenDone() {
        val c = startCore()
        now = 10_100
        launch()
        now = 10_300
        requests.single().second()
        settle()

        assertTrue(c.appStartTracker!!.isDone)
        Robolectric.buildActivity(SplashActivity::class.java).setup()
        assertEquals("the tracker no longer hears Activities", 1, requests.size)
    }

    @Test
    fun initAfterFirstActivityResumedEmitsNothing() {
        val first = Robolectric.buildActivity(PlainActivity::class.java).setup()
        val c = startCore()
        first.pause() // the real order: the first Activity pauses before the second is created
        now = 10_200
        launch()
        now = 10_400
        requests.lastOrNull()?.second?.invoke()

        assertTrue(settle().appStarts().isEmpty())
        assertTrue(c.appStartTracker!!.isDone)
    }

    @Test
    fun optOutBeforeFirstFrameEmitsNothing() {
        val c = startCore()
        now = 10_100
        launch()
        c.optOut(true)
        now = 10_500
        requests.single().second()

        assertTrue(settle().appStarts().isEmpty())
    }

    @Test
    fun disabledAtInitCreatesNoTracker() {
        KillSwitch(SharedPrefsStore(app, TransportConfigStore.PREFS_NAME), Clock.SYSTEM).optedOut = true

        val c = startCore()

        assertNull(c.appStartTracker)
    }

    @Test
    fun backgroundBeforeFirstFrameEmitsNothing() {
        startCore()
        now = 10_100
        launch()
        ProcessLifecycleTestUtil.start()
        ProcessLifecycleTestUtil.stop()
        now = 10_900
        requests.single().second()

        assertTrue(settle().appStarts().isEmpty())
    }

    @Test
    fun defaultFactoryUsesTheProcessStartAndTheRealHook() {
        val c = startCore(fake = false)

        assertNotNull(c.appStartTracker)
        launch() // no exception; the real hook only fires on a real draw
        settle()
    }

    @Test
    fun shutdownUnregistersTheTracker() {
        startCore()
        now = 10_100
        launch()
        ARTracker.resetForTest()
        now = 10_500
        requests.single().second() // must not throw

        assertTrue(seen.appStarts().isEmpty())
    }
}
