package id.art.tracker.core

import android.app.Application
import android.os.Looper
import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestinationBuilder
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.Navigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import android.app.Activity
import android.widget.TextView
import id.art.tracker.LogLevel
import id.art.tracker.ReplayConfig
import id.art.tracker.BeforeSend
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.ManualDispatcher
import id.art.tracker.replay.ReplayController
import id.art.tracker.replay.capture.ViewWalker
import id.art.tracker.testutil.FakeReplayBackend
import id.art.tracker.transport.FlusherRegistry
import id.art.tracker.transport.NewReplayBatch
import id.art.tracker.transport.ReplayStore
import id.art.tracker.TelemetryItem
import id.art.tracker.lifecycle.AnnotatedActivity
import id.art.tracker.lifecycle.FragmentSupport
import id.art.tracker.lifecycle.HostActivity
import id.art.tracker.lifecycle.ListFragment
import id.art.tracker.lifecycle.NavBinder
import id.art.tracker.lifecycle.PlainActivity
import id.art.tracker.testutil.FakeClock
import id.art.tracker.testutil.ProcessLifecycleTestUtil
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ARTrackerCoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val seen = CopyOnWriteArrayList<TelemetryItem>()
    private val config = configWith()

    private fun configWith(
        sessionTimeoutMs: Long = 1_800_000,
        endpoint: String = "http://127.0.0.1:9",
        replay: ReplayConfig = ReplayConfig(),
    ) = ARTrackerConfig(
        endpoint = endpoint,
        apiKey = "art_live_" + "a".repeat(43),
        environment = "test",
        appVersion = "1.0",
        sessionTimeoutMs = sessionTimeoutMs,
        replay = replay,
        beforeSend = BeforeSend { it.also(seen::add) },
    )

    /** Starts a core on a fake clock so that sessions can be aged without sleeping. */
    private fun startCoreWith(clock: FakeClock, sessionTimeoutMs: Long = 1_000) {
        val core = ARTrackerCore(app, configWith(sessionTimeoutMs), clock)
        ARTrackerCore.install(core)
        core.start()
    }

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ClassAvailability.resetOverrides()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ClassAvailability.resetOverrides()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
        app.deleteDatabase(ReplayStore.DB_NAME)
    }

    private fun core(): ARTrackerCore = ARTrackerCore.instanceOrNull()!!

    private fun settle(): List<TelemetryItem> {
        runBlocking { withTimeout(15_000) { core().awaitIdle() } }
        return seen.toList()
    }

    private fun List<TelemetryItem>.named(name: String) = filter { it.name == name }

    private fun List<TelemetryItem>.screens() =
        named("screen_view").map { "${it.attributes["screen"]}:${it.attributes["type"]}" }

    private fun disableOptionalDeps() {
        listOf(
            "okhttp3.OkHttpClient",
            "androidx.fragment.app.FragmentActivity",
            "androidx.compose.ui.platform.AndroidComposeView",
            "androidx.navigation.NavController",
        ).forEach { ClassAvailability.overrides[it] = false }
    }

    private fun replayBatch(session: String = "session-xyz-1") =
        NewReplayBatch(session, byteArrayOf(1, 2, 3), 1, 1_000, 2_000, false, null, "test")

    @Test
    fun optOut_clearsStoredReplayBatches() {
        val backend = FakeReplayBackend()
        val store = ReplayStore(app, Clock.SYSTEM)
        try {
            store.enqueue(replayBatch())
            store.enqueue(replayBatch())
            ARTracker.init(app, configWith(endpoint = backend.endpoint))

            ARTracker.optOut(true)
            settle()

            assertEquals(0, store.pendingCount())
            assertTrue(backend.requests.isEmpty())
        } finally {
            store.close()
            backend.shutdown()
        }
    }

    @Test
    fun init_resetsOrphanedAndPurgesOldReplayBatches() {
        val clock = FakeClock(1_700_000_000_000L)
        val store = ReplayStore(app, clock)
        try {
            clock.advance(-8L * 24 * 60 * 60 * 1000)
            store.enqueue(replayBatch("session-old-1"))
            clock.advance(8L * 24 * 60 * 60 * 1000)
            store.enqueue(replayBatch("session-orphan-1"))
            // Both claimed by a process that died before it settled them.
            assertEquals("session-old-1", store.claim()!!.sessionId)
            assertEquals("session-orphan-1", store.claim()!!.sessionId)
            assertEquals(0, store.pendingCount())

            startCoreWith(clock)
            settle()

            assertEquals(1, store.pendingCount())
            assertEquals("session-orphan-1", store.claim()!!.sessionId)
        } finally {
            store.close()
        }
    }

    @Test
    fun init_registersTheReplayFlusher_whenAReplayDatabaseAlreadyExists() {
        val store = ReplayStore(app, Clock.SYSTEM)
        try {
            store.enqueue(replayBatch())
            ARTracker.init(app, configWith(endpoint = "http://127.0.0.1:9"))
            settle()

            // The background worker must find the live flusher instead of building a second one next to it.
            assertTrue(FlusherRegistry.replay != null)
        } finally {
            store.close()
        }
    }

    @Test
    fun init_doesNotRegisterAReplayFlusher_whenThereIsNoReplayDatabase() {
        ARTracker.init(app, config)
        settle()

        assertEquals(null, FlusherRegistry.replay)
        assertFalse(app.getDatabasePath(ReplayStore.DB_NAME).exists())
    }

    @Test
    fun replayStoreNotCreated_whenReplayNeverUsed() {
        ARTracker.init(app, config)
        ARTracker.flush()
        settle()

        assertFalse(app.getDatabasePath(ReplayStore.DB_NAME).exists())
    }

    @Test
    fun errorLogItem_marksOpenReplayBatchHasError() {
        val backend = FakeReplayBackend()
        try {
            val dispatcher = ManualDispatcher()
            val core = ARTrackerCore(
                app,
                configWith(endpoint = backend.endpoint, replay = ReplayConfig(enabled = true, sampleRate = 1.0)),
                replayFactory = { c, handler ->
                    ReplayController.create(c, ViewWalker(MaskingPolicy(c.config.privacy)), handler, { _, _ -> true }, dispatcher)
                },
            )
            ARTrackerCore.install(core)
            core.start()
            ProcessLifecycleTestUtil.start()
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            activity.setContentView(TextView(activity).apply { text = "hello replay" })
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
            core.replayController!!.captureFrame()
            dispatcher.runAll()

            ARTracker.log(LogLevel.ERROR, "something broke")
            settle()
            dispatcher.runAll()
            core.replayController!!.flushChunks()
            dispatcher.runAll()

            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
            while (backend.requestsTo(FakeReplayBackend.ACK).isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
            val ack = backend.requestsTo(FakeReplayBackend.ACK).single().json()
            assertTrue(ack.getBoolean("has_error"))
        } finally {
            backend.shutdown()
        }
    }

    @Test
    fun init_withoutOptionalDeps_doesNotTouchOptionalClasses() {
        disableOptionalDeps()
        val fragmentCalls = FragmentSupport.registerCalls.get()
        val navCalls = NavBinder.bindCalls.get()

        ARTracker.init(app, config)
        val activity = Robolectric.buildActivity(HostActivity::class.java).setup() // a FragmentActivity
        activity.get().supportFragmentManager.beginTransaction().add(ListFragment(), "f").commitNow()
        ARTracker.trackNavController(Any())
        ARTracker.screen("Manual")
        val items = settle()

        assertEquals(fragmentCalls, FragmentSupport.registerCalls.get())
        assertEquals(navCalls, NavBinder.bindCalls.get())
        // The activity is still tracked, the fragment (which needs the optional dependency) is not.
        assertEquals(listOf("HostActivity:activity", "Manual:manual"), items.screens())
    }

    @Test
    fun init_withOptionalDeps_doesTouchThem_controlForTheTestAbove() {
        val fragmentCalls = FragmentSupport.registerCalls.get()

        ARTracker.init(app, config)
        val activity = Robolectric.buildActivity(HostActivity::class.java).setup()
        activity.get().supportFragmentManager.beginTransaction().add(ListFragment(), "f").commitNow()
        val items = settle()

        assertTrue(FragmentSupport.registerCalls.get() > fragmentCalls)
        assertEquals(listOf("HostActivity:activity", "ListFragment:fragment"), items.screens())
    }

    @Test
    fun manualScreen_goesThroughScreenTracker() {
        ARTracker.init(app, config)
        ARTracker.screen("Home")
        ARTracker.screen("Home")
        ARTracker.screen("Cart")
        val screens = settle().named("screen_view")

        assertEquals(2, screens.size)
        assertEquals(mapOf("screen" to "Home", "type" to "manual"), screens[0].attributes)
        assertEquals(mapOf("screen" to "Cart", "previous" to "Home", "type" to "manual"), screens[1].attributes)
        assertEquals("Cart", core().screens.currentScreen)
    }

    @Test
    fun activityResume_afterInit_isTracked_andAnnotationWins() {
        ARTracker.init(app, config)
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        Robolectric.buildActivity(AnnotatedActivity::class.java).setup()

        assertEquals(listOf("PlainActivity:activity", "Checkout Flow:activity"), settle().screens())
    }

    @Test
    fun initWhileProcessStopped_isNotForeground_untilProcessStarts_thenBackground() {
        ARTracker.init(app, config)
        assertTrue(settle().named("app_foreground").isEmpty())

        ProcessLifecycleTestUtil.start()
        assertEquals(1, settle().named("app_foreground").size)
        assertEquals(1, settle().named("session_start").size)

        ProcessLifecycleTestUtil.stop()
        assertEquals(1, settle().named("app_background").size)
    }

    @Test
    fun initAfterProcessAlreadyStarted_entersForegroundExactlyOnce() {
        ProcessLifecycleTestUtil.start()

        ARTracker.init(app, config)
        val items = settle()

        assertEquals(1, items.named("app_foreground").size)
        assertEquals(1, items.named("session_start").size)
    }

    @Test
    fun shutdown_removesProcessObserver_andActivityCallbacks() {
        val registry = ProcessLifecycleTestUtil.registry
        val base = registry.observerCount
        ARTracker.init(app, config)
        assertEquals(base + 1, registry.observerCount)
        val oldCore = core()
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        assertEquals("PlainActivity", oldCore.screens.currentScreen)

        ARTracker.resetForTest()

        assertEquals(base, registry.observerCount)
        Robolectric.buildActivity(AnnotatedActivity::class.java).setup()
        assertEquals("PlainActivity", oldCore.screens.currentScreen) // old core no longer hears activities
        ProcessLifecycleTestUtil.start()
        assertFalse(seen.any { it.name == "app_foreground" })
    }

    @Test
    fun initOffMainThread_registersProcessObserverOnMainLooper() {
        val registry = ProcessLifecycleTestUtil.registry
        val base = registry.observerCount

        val t = Thread { ARTracker.init(app, config) }
        t.start()
        t.join(10_000)
        assertTrue(ARTracker.isInitialized())
        assertEquals("not yet: the registration waits for the main looper", base, registry.observerCount)

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(base + 1, registry.observerCount)
    }

    @Test
    fun trackNavController_emitsComposeScreens_andIgnoresDuplicateRegistration() {
        ARTracker.init(app, config)
        val nav = NavController(app)
        nav.navigatorProvider.addNavigator(TestNavigator())
        nav.graph = nav.createGraph(startDestination = "home") {
            screen("home")
            screen("cart")
        }
        ARTracker.trackNavController(nav)
        ARTracker.trackNavController(nav)
        nav.navigate("cart")
        nav.navigate("home")
        val items = settle()

        // The current destination ("home") is reported when the listener is added, then each change.
        assertEquals(listOf("home:compose", "cart:compose", "home:compose"), items.screens())
        assertEquals("cart", items.named("screen_view")[2].attributes["previous"])
    }

    private fun newNavController(): NavController {
        val nav = NavController(app)
        nav.navigatorProvider.addNavigator(TestNavigator())
        nav.graph = nav.createGraph(startDestination = "home") {
            screen("home")
            screen("cart")
        }
        return nav
    }

    @Test
    fun trackNavController_doesNotKeepTheControllerAlive() {
        ARTracker.init(app, config)
        var nav: NavController? = newNavController()
        val ref = WeakReference(nav)
        ARTracker.trackNavController(nav!!)
        nav = null

        // Bounded retry: a collection is only a request, so poll for a few seconds before giving up.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc()
            @Suppress("DEPRECATION")
            Runtime.getRuntime().runFinalization()
            Thread.sleep(50)
        }
        assertEquals("the SDK still holds the NavController", null, ref.get())
    }

    @Test
    fun shutdown_detachesNavListener_ofAControllerThatIsStillAlive() {
        ARTracker.init(app, config)
        val nav = newNavController()
        ARTracker.trackNavController(nav)
        val oldCore = core()
        assertEquals("home", oldCore.screens.currentScreen)

        ARTracker.resetForTest()
        nav.navigate("cart")

        assertEquals("home", oldCore.screens.currentScreen)
    }

    private fun sessionOf(items: List<TelemetryItem>, name: String) = items.named(name).map { it.sessionId }

    @Test
    fun sessionTimeoutRotation_reportsVisibleScreenInNewSession() {
        val clock = FakeClock(1_700_000_000_000)
        startCoreWith(clock, sessionTimeoutMs = 1_000)
        ProcessLifecycleTestUtil.start()
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        ProcessLifecycleTestUtil.stop()
        clock.advance(5_000)
        ProcessLifecycleTestUtil.start() // same Activity stays visible: no new resume happens

        val items = settle()
        val views = items.named("screen_view")
        val starts = sessionOf(items, "session_start")
        assertEquals(2, starts.size)
        assertEquals(2, views.size)
        assertEquals(starts[0], views[0].sessionId)
        assertEquals(starts[1], views[1].sessionId)
        assertEquals(mapOf("screen" to "PlainActivity", "type" to "activity"), views[1].attributes)
        // Order inside the new session: session_start, then the screen it opens on.
        assertTrue(items.indexOf(items.named("session_start")[1]) < items.indexOf(views[1]))
    }

    @Test
    fun shortBackground_sameSession_noDuplicateScreenView() {
        val clock = FakeClock(1_700_000_000_000)
        startCoreWith(clock, sessionTimeoutMs = 60_000)
        ProcessLifecycleTestUtil.start()
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        ProcessLifecycleTestUtil.stop()
        clock.advance(1_000)
        ProcessLifecycleTestUtil.start()

        val items = settle()
        assertEquals(1, items.named("session_start").size)
        assertEquals(1, items.named("screen_view").size)
    }

    @Test
    fun reset_logoutRotation_reportsVisibleScreenInNewSession_andPreviousStartsFresh() {
        ARTracker.init(app, config)
        ProcessLifecycleTestUtil.start()
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        ARTracker.screen("Cart")

        ARTracker.reset()
        ARTracker.screen("Pay")
        val items = settle()

        val views = items.named("screen_view")
        val starts = sessionOf(items, "session_start")
        assertEquals(2, starts.size)
        assertEquals(listOf("PlainActivity", "Cart", "Cart", "Pay"), views.map { it.attributes["screen"] })
        // The re-report opens the new session: right session id, and no `previous` from the old one.
        assertEquals(starts[1], views[2].sessionId)
        assertEquals(mapOf("screen" to "Cart", "type" to "manual"), views[2].attributes)
        // Normal tracking resumes inside the new session.
        assertEquals(starts[1], views[3].sessionId)
        assertEquals("Cart", views[3].attributes["previous"])
        assertEquals(starts[0], views[0].sessionId)
        assertEquals(starts[0], views[1].sessionId)
    }
}

/** A navigator whose destinations are plain nodes: enough for the controller to dispatch destination changes. */
@Navigator.Name("test")
class TestNavigator : Navigator<NavDestination>() {
    override fun createDestination() = NavDestination(this)

    override fun navigate(
        destination: NavDestination,
        args: Bundle?,
        navOptions: NavOptions?,
        navigatorExtras: Extras?,
    ): NavDestination = destination
}

private fun NavGraphBuilder.screen(route: String) {
    addDestination(NavDestinationBuilder<NavDestination>(provider.getNavigator(TestNavigator::class.java), route).build())
}
