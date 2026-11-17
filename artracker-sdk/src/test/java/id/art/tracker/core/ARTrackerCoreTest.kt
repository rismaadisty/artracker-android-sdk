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
import android.view.MotionEvent
import android.widget.TextView
import id.art.tracker.LogLevel
import id.art.tracker.ReplayConfig
import id.art.tracker.BeforeSend
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.ManualDispatcher
import id.art.tracker.replay.ReplayController
import id.art.tracker.replay.ReplaySink
import id.art.tracker.testutil.FakeReplayBackend
import id.art.tracker.testutil.testReplayController
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
import org.junit.Assert.assertNotEquals
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

    private fun startCoreWithBuildId(reader: (android.content.Context) -> String?) {
        val core = ARTrackerCore(app, configWith(), FakeClock(1_700_000_000_000L), buildIdReader = reader)
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
    fun sessionStartOfTheRunCarriesTheBuildId() {
        startCoreWithBuildId { "git-abc" }
        ProcessLifecycleTestUtil.start()

        val start = settle().named("session_start").first()

        assertEquals("git-abc", start.attributes["build_id"])
    }

    @Test
    fun theBuildIdIsReadBeforeTheQueueDatabaseIsOpened() {
        // The first background task reads it first, so a crash right after init has the shortest possible window
        // without it (the crash handler never waits for it). Opening the database is the first slow thing that follows.
        var databaseExistedAtRead: Boolean? = null
        startCoreWithBuildId {
            databaseExistedAtRead = app.getDatabasePath("artracker.db").exists()
            "git-abc"
        }
        ProcessLifecycleTestUtil.start()
        settle()

        assertEquals(false, databaseExistedAtRead)
    }

    @Test
    fun initDoesNotReadTheBuildIdOnTheMainThread() {
        val loopers = CopyOnWriteArrayList<Looper?>()
        startCoreWithBuildId { loopers += Looper.myLooper(); "git-abc" }
        ProcessLifecycleTestUtil.start()
        settle()

        assertTrue("the reader ran", loopers.isNotEmpty())
        assertTrue("never on the main looper", loopers.none { it === Looper.getMainLooper() })
    }

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
    fun replayOff_touchStillExtendsSession() {
        val clock = FakeClock(1_700_000_000_000L)
        val core = ARTrackerCore(app, configWith(), clock) // replay disabled by default
        ARTrackerCore.install(core)
        core.start()
        ProcessLifecycleTestUtil.start()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val id = core.session.currentId()
        fun down() = activity.window.callback.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0))

        clock.advance(29 * 60_000L)
        down()
        clock.advance(2 * 60_000L) // 31 min after the start, but only 2 min after the last touch
        assertFalse(core.session.isExpired())
        down()
        assertEquals(id, core.session.currentId())
    }

    @Test
    fun rageTaps_reachTheAckAsHasRageClick() {
        val backend = FakeReplayBackend()
        try {
            val dispatcher = ManualDispatcher()
            val core = ARTrackerCore(
                app,
                configWith(endpoint = backend.endpoint, replay = ReplayConfig(enabled = true, sampleRate = 1.0)),
                replayFactory = { c, handler -> testReplayController(c, handler, dispatcher) },
            )
            ARTrackerCore.install(core)
            core.start()
            ProcessLifecycleTestUtil.start()
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            activity.setContentView(TextView(activity).apply { text = "hello replay" })
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
            core.replayController!!.captureFrame()
            dispatcher.runAll()

            // Three taps on one spot, all within a second of the SDK clock.
            repeat(3) { i ->
                activity.window.callback.dispatchTouchEvent(MotionEvent.obtain(0, i * 50L, MotionEvent.ACTION_DOWN, 100f, 200f, 0))
                activity.window.callback.dispatchTouchEvent(MotionEvent.obtain(0, i * 50L + 10, MotionEvent.ACTION_UP, 100f, 200f, 0))
            }
            dispatcher.runAll()
            core.replayController!!.flushChunks()
            dispatcher.runAll()

            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
            while (backend.requestsTo(FakeReplayBackend.ACK).isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
            val ack = backend.requestsTo(FakeReplayBackend.ACK).single().json()
            assertTrue(ack.getBoolean("has_rage_click"))
            assertFalse(ack.getBoolean("has_error"))
        } finally {
            backend.shutdown()
        }
    }

    @Test
    fun errorLogItem_marksOpenReplayBatchHasError() {
        val backend = FakeReplayBackend()
        try {
            val dispatcher = ManualDispatcher()
            val core = ARTrackerCore(
                app,
                configWith(endpoint = backend.endpoint, replay = ReplayConfig(enabled = true, sampleRate = 1.0)),
                replayFactory = { c, handler -> testReplayController(c, handler, dispatcher) },
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

    // region identify

    private fun List<TelemetryItem>.identifies() = named("\$identify")

    @Test
    fun identify_fromAnonymous_keepsSession() {
        startCoreWith(FakeClock(1_700_000_000_000), sessionTimeoutMs = 60_000)
        val before = ARTracker.diagnostics().sessionId
        ARTracker.identify("A")
        val after = ARTracker.diagnostics().sessionId

        val items = settle()
        assertEquals(before, after)
        assertEquals(0, items.named("session_end").size)
        assertEquals(1, items.named("session_start").size)
        val identify = items.identifies().single()
        assertEquals("A", identify.userId)
        assertEquals(before, identify.sessionId)
    }

    @Test
    fun identify_userSwitch_rotatesSession() {
        startCoreWith(FakeClock(1_700_000_000_000), sessionTimeoutMs = 60_000)
        ARTracker.identify("A")
        val oldId = ARTracker.diagnostics().sessionId!!
        ARTracker.identify("B")
        val newId = ARTracker.diagnostics().sessionId!!
        assertNotEquals(oldId, newId)

        val items = settle()
        val firstEnd = items.indexOf(items.named("session_end").single())
        val tail = items.drop(firstEnd).filter { it.name in setOf("session_end", "session_start", "\$identify") }
        assertEquals(listOf("session_end", "session_start", "\$identify"), tail.map { it.name })
        assertEquals("A", tail[0].userId)
        assertEquals(oldId, tail[0].sessionId)
        assertEquals("B", tail[1].userId)
        assertEquals(newId, tail[1].sessionId)
        assertEquals("B", tail[2].userId)
        assertEquals(newId, tail[2].sessionId)
    }

    @Test
    fun identify_sameUserSameTraits_emitsOnce_perProcess() {
        val clock = FakeClock(1_700_000_000_000)
        startCoreWith(clock, sessionTimeoutMs = 60_000)
        repeat(3) { ARTracker.identify("A", mapOf("plan" to "pro")) }
        assertEquals(1, settle().identifies().size)

        ARTracker.identify("A", mapOf("plan" to "team"))
        assertEquals(2, settle().identifies().size)

        // A new process (a new core over the same stored user) has no in-memory state: it must emit again.
        ARTracker.resetForTest()
        seen.clear()
        startCoreWith(clock, sessionTimeoutMs = 60_000)
        ARTracker.identify("A", mapOf("plan" to "team"))
        val items = settle()
        assertEquals(1, items.identifies().size)
        assertEquals(0, items.named("session_end").size)
    }

    @Test
    fun identify_whileOptedOut_doesNotPoisonDedupe() {
        startCoreWith(FakeClock(1_700_000_000_000), sessionTimeoutMs = 60_000)
        ARTracker.optOut(true)
        ARTracker.identify("A")
        ARTracker.optOut(false)
        ARTracker.identify("A")

        val items = settle()
        assertEquals(1, items.identifies().size)
        assertEquals("A", items.identifies().single().userId)
    }

    @Test
    fun identify_afterOptOutDiscardedTheQueuedIdentify_sendsAgainOnceOptedBackIn() {
        startCoreWith(FakeClock(1_700_000_000_000), sessionTimeoutMs = 60_000)
        ARTracker.identify("A", mapOf("plan" to "pro"))
        assertEquals(1, settle().identifies().size)
        ARTracker.optOut(true) // discards what was queued and not yet sent
        ARTracker.optOut(false)
        ARTracker.identify("A", mapOf("plan" to "pro"))

        assertEquals(2, settle().identifies().size)
    }

    @Test
    fun identify_concurrentSwitchToTheSameUser_rotatesOnce() {
        startCoreWith(FakeClock(1_700_000_000_000), sessionTimeoutMs = 60_000)
        val rounds = 25
        repeat(rounds) { i ->
            ARTracker.identify("A$i")
            val start = java.util.concurrent.CountDownLatch(1)
            val threads = (0 until 6).map {
                Thread { start.await(); ARTracker.identify("B$i", mapOf("n" to 1)) }.also(Thread::start)
            }
            start.countDown()
            threads.forEach(Thread::join)
        }

        val items = settle()
        // Per round: exactly one rotation (one session_end stamped with the previous user) and one $identify for B.
        assertEquals(rounds, items.identifies().count { (it.userId ?: "").startsWith("B") })
        val endsOfA = items.named("session_end").count { (it.userId ?: "").startsWith("A") }
        assertEquals(rounds, endsOfA)
        // The B -> A(next round) switches close one session each; a doubled rotation would add more.
        assertEquals(rounds - 1, items.named("session_end").count { (it.userId ?: "").startsWith("B") })
    }

    @Test
    fun userForSession_isThePreviousUserForTheSessionThatJustEnded() {
        startCoreWith(FakeClock(1_700_000_000_000), sessionTimeoutMs = 60_000)
        ARTracker.identify("A")
        val oldId = ARTracker.diagnostics().sessionId!!
        ARTracker.identify("B")
        val newId = ARTracker.diagnostics().sessionId!!

        assertEquals("A", core().userForSession(oldId))
        assertEquals("B", core().userForSession(newId))
        ARTracker.reset()
        assertEquals("B", core().userForSession(newId))
        assertEquals(null, core().userForSession(ARTracker.diagnostics().sessionId!!))
    }

    @Test
    fun identify_userSwitchFromABackgroundThread_rotatesReplaySession() {
        val sampled = CopyOnWriteArrayList<String>()
        val dispatcher = ManualDispatcher()
        val core = ARTrackerCore(
            app,
            configWith(replay = ReplayConfig(enabled = true, sampleRate = 1.0)),
            replayFactory = { c, handler ->
                testReplayController(c, handler, dispatcher, sampler = { id, _ -> sampled += id; true })
            },
            replaySink = ReplaySink { },
        )
        ARTrackerCore.install(core)
        core.start()
        ProcessLifecycleTestUtil.start()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(TextView(activity).apply { text = "hello replay" })
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(java.time.Duration.ofMillis(1_000))
        ARTracker.identify("A")
        val oldId = ARTracker.diagnostics().sessionId!!

        Thread { ARTracker.identify("B") }.also { it.start() }.join()
        val newId = ARTracker.diagnostics().sessionId!!
        looper.idle()
        settle()
        dispatcher.runAll()

        assertNotEquals(oldId, newId)
        assertEquals(newId, sampled.last())
        assertEquals("B", core.userForSession(newId))
        assertEquals("A", core.userForSession(oldId))
    }

    @Test
    fun identify_userSwitch_rotatesReplaySession() {
        val sampled = CopyOnWriteArrayList<String>()
        val chunks = CopyOnWriteArrayList<NewReplayBatch>()
        val dispatcher = ManualDispatcher()
        val core = ARTrackerCore(
            app,
            configWith(replay = ReplayConfig(enabled = true, sampleRate = 1.0)),
            replayFactory = { c, handler ->
                testReplayController(c, handler, dispatcher, sampler = { id, _ -> sampled += id; true })
            },
            replaySink = ReplaySink { chunks += it },
        )
        ARTrackerCore.install(core)
        core.start()
        ProcessLifecycleTestUtil.start()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(TextView(activity).apply { text = "hello replay" })
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(java.time.Duration.ofMillis(1_000))

        ARTracker.identify("A")
        val oldId = ARTracker.diagnostics().sessionId!!
        core.replayController!!.captureFrame()
        dispatcher.runAll()

        ARTracker.identify("B")
        val newId = ARTracker.diagnostics().sessionId!!
        assertNotEquals(oldId, newId)
        looper.idle()
        settle()
        dispatcher.runAll()
        assertEquals(newId, sampled.last())

        core.replayController!!.captureFrame()
        dispatcher.runAll()
        core.replayController!!.flushChunks()
        dispatcher.runAll()
        assertTrue("a chunk of the new session was produced", chunks.any { it.sessionId == newId })
        assertTrue("no chunk of the new session carries the old id", chunks.filter { it.userId == "B" }.all { it.sessionId == newId })
    }

    // endregion

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

    /** A first process that ran in the foreground and went to the background; returns its session id. A "death" follows. */
    private fun firstProcessThenDeath(clock: FakeClock): String {
        startCoreWith(clock, sessionTimeoutMs = 60_000)
        ProcessLifecycleTestUtil.start()
        ProcessLifecycleTestUtil.stop()
        val id = settle().named("session_start").single().sessionId
        ARTracker.resetForTest() // the process is gone; its prefs stay
        seen.clear()
        return id
    }

    @Test
    fun processRestart_withinTimeout_keepsSessionId() {
        val clock = FakeClock(1_700_000_000_000)
        val id = firstProcessThenDeath(clock)
        clock.advance(30_000)
        startCoreWith(clock, sessionTimeoutMs = 60_000)
        ProcessLifecycleTestUtil.start()

        val items = settle()
        assertTrue(items.named("session_start").isEmpty())
        assertTrue(items.named("session_end").isEmpty())
        assertEquals(id, items.named("app_foreground").single().sessionId)
    }

    @Test
    fun processRestart_afterTimeout_endsOldSessionThenStartsNew() {
        val clock = FakeClock(1_700_000_000_000)
        val id = firstProcessThenDeath(clock)
        clock.advance(120_000)
        startCoreWith(clock, sessionTimeoutMs = 60_000)
        ProcessLifecycleTestUtil.start()

        val items = settle()
        val end = items.named("session_end").single()
        val start = items.named("session_start").single()
        assertEquals(id, end.sessionId)
        assertNotEquals(id, start.sessionId)
        assertTrue(items.indexOf(end) < items.indexOf(start))
        assertEquals(start.sessionId, items.named("app_foreground").single().sessionId)
    }

    @Test
    fun processRestartInBackground_keepsStoredSession_untilForeground() {
        val clock = FakeClock(1_700_000_000_000)
        val id = firstProcessThenDeath(clock)
        clock.advance(120_000)
        startCoreWith(clock, sessionTimeoutMs = 60_000) // started by a push or a job: never in the foreground
        core().track("push_received", emptyMap())

        val items = settle()
        assertEquals(id, items.named("push_received").single().sessionId)
        assertTrue(items.named("session_start").isEmpty())
        assertTrue(items.named("session_end").isEmpty())
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
