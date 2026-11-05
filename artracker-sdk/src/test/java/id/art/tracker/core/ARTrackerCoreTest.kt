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
import id.art.tracker.BeforeSend
import id.art.tracker.TelemetryItem
import id.art.tracker.lifecycle.AnnotatedActivity
import id.art.tracker.lifecycle.FragmentSupport
import id.art.tracker.lifecycle.HostActivity
import id.art.tracker.lifecycle.ListFragment
import id.art.tracker.lifecycle.NavBinder
import id.art.tracker.lifecycle.PlainActivity
import id.art.tracker.testutil.ProcessLifecycleTestUtil
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
    private val config = ARTrackerConfig(
        endpoint = "http://127.0.0.1:9",
        apiKey = "art_live_" + "a".repeat(43),
        environment = "test",
        appVersion = "1.0",
        beforeSend = BeforeSend { it.also(seen::add) },
    )

    @Before
    fun setUp() {
        ARTracker.resetForTest()
        ClassAvailability.resetOverrides()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
    }

    @After
    fun tearDown() {
        ARTracker.resetForTest()
        ClassAvailability.resetOverrides()
        ProcessLifecycleTestUtil.stop()
        app.deleteDatabase("artracker.db")
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
