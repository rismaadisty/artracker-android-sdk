package id.art.tracker.lifecycle

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
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

    private fun config() = ARTrackerConfig(
        endpoint = "http://127.0.0.1:9",
        apiKey = "art_live_" + "a".repeat(43),
        environment = "test",
        appVersion = "1.0",
        beforeSend = BeforeSend { it.also(seen::add) },
    )

    /** A core whose tracker uses the fake hook and the test clock; null factory = the real one. */
    private fun startCore(fake: Boolean = true): ARTrackerCore {
        val factory: ((( AppStart) -> Unit, () -> Unit) -> AppStartTracker?)? = if (fake) {
            { onAppStart, onDone -> AppStartTracker(10_000L, true, { now }, hook, onAppStart, onDone) }
        } else {
            null
        }
        val c = ARTrackerCore(app, config(), appStartFactory = factory)
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
    fun coldStartReachesThePipeline() {
        startCore()
        ProcessLifecycleTestUtil.start()
        now = 10_100
        launch()
        now = 10_640
        requests.single().second()

        val items = settle()
        val start = items.appStarts().single()
        assertEquals(mapOf("type" to "cold", "duration_ms" to 640L, "first_screen" to "PlainActivity"), start.attributes)
        assertEquals(id.art.tracker.ItemType.EVENT, start.type)
        assertEquals(items.first { it.name == "session_start" }.sessionId, start.sessionId)
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
