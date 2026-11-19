package id.art.tracker.lifecycle

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SplashActivity : Activity()

/** The decisions of the cold start measurement, without a real first frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStartTrackerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val processStart = 10_000L
    private var now = processStart
    private val starts = ArrayList<AppStart>()
    private val dones = ArrayList<ColdStartDrop?>()

    private class Request(val activity: Activity, val onFrame: () -> Unit)

    private class FakeHook : FirstFrameHook {
        val requests = ArrayList<Request>()
        var cancels = 0

        override fun await(activity: Activity, onFirstFrame: () -> Unit): () -> Unit {
            requests += Request(activity, onFirstFrame)
            return { cancels++ }
        }
    }

    private val hook = FakeHook()
    private var registered: AppStartTracker? = null

    private fun tracker(
        startedInForeground: Boolean = true,
        onAppStart: (AppStart) -> Unit = { starts += it },
    ): AppStartTracker = AppStartTracker(
        processStartUptimeMs = processStart,
        startedInForeground = startedInForeground,
        uptime = { now },
        firstFrame = hook,
        onAppStart = onAppStart,
        onDone = { dones += it },
    ).also {
        app.registerActivityLifecycleCallbacks(it)
        registered = it
    }

    private fun assertOneStart(duration: Long, screen: String) {
        assertEquals(1, starts.size)
        val s = starts.single()
        assertEquals(StartType.COLD, s.type)
        assertEquals(duration, s.durationMs)
        assertEquals(screen, s.firstScreen)
        assertEquals("phases sum to the duration", duration, s.phases.sumOf { it.ms })
    }

    @After
    fun tearDown() {
        registered?.let(app::unregisterActivityLifecycleCallbacks)
    }

    @Test
    fun coldStartIsMeasuredToTheFirstFrame() {
        val t = tracker()
        now = 10_300
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now = 10_850
        hook.requests.single().onFrame()

        assertOneStart(850, "PlainActivity")
        assertTrue(t.isDone)
        assertEquals(1, dones.size)
    }

    @Test
    fun firstScreenHonoursTheAnnotation() {
        tracker()
        now = 10_200
        Robolectric.buildActivity(AnnotatedActivity::class.java).setup()
        now = 10_400
        hook.requests.single().onFrame()

        assertEquals("Checkout Flow", starts.single().firstScreen)
    }

    @Test
    fun firstCreateLongAfterProcessStartEmitsNothing() {
        val t = tracker()
        now = processStart + AppStartTracker.MAX_FIRST_CREATE_DELAY_MS + 1
        Robolectric.buildActivity(PlainActivity::class.java).setup()

        assertTrue(starts.isEmpty())
        assertTrue(t.isDone)
        assertTrue("the hook is never asked", hook.requests.isEmpty())
    }

    @Test
    fun aSlowDeviceFirstActivityAfterInit_isStillACountedColdStart() {
        // Measured on a physical device (debug, non-AOT) 1.45 s after init, and 1.1 to 6 s on a loaded emulator, in a
        // process that was in the foreground at init: a user launch, not a push, so it must be reported.
        val t = tracker()
        now = processStart + 1_450
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now += 300
        hook.requests.single().onFrame()

        assertOneStart(1_750, "PlainActivity")
        assertTrue(t.isDone)
    }

    @Test
    fun createExactlyAtTheThresholdStillCounts() {
        tracker()
        now = processStart + AppStartTracker.MAX_FIRST_CREATE_DELAY_MS
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now += 100
        hook.requests.single().onFrame()

        assertEquals(1, starts.size)
    }

    @Test
    fun notForegroundAtInitEmitsNothing() {
        val t = tracker(startedInForeground = false)
        now = 10_100
        Robolectric.buildActivity(PlainActivity::class.java).setup()

        assertTrue(starts.isEmpty())
        assertTrue(t.isDone)
        assertTrue(hook.requests.isEmpty())
    }

    @Test
    fun firstCallbackOtherThanCreateEmitsNothing() {
        val t = tracker()
        // init ran after the first Activity was created: the first thing heard is a later lifecycle callback.
        t.onActivityResumed(Robolectric.buildActivity(PlainActivity::class.java).get())

        assertTrue(t.isDone)
        assertTrue(starts.isEmpty())
        assertTrue(hook.requests.isEmpty())
    }

    @Test
    fun splashThatFinishesBeforeDrawingMeasuresTheNextActivity() {
        tracker()
        now = 10_100
        val splash = Robolectric.buildActivity(SplashActivity::class.java).create()
        splash.get().finish()
        splash.destroy()
        now = 10_200
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now = 10_700
        hook.requests.last().onFrame()

        assertOneStart(700, "PlainActivity")
    }

    @Test
    fun aSlowApplicationOnCreate_isStillACountedColdStart() {
        // The process started 5 s ago (providers, a heavy Application.onCreate); the tracker is created at init, 0.3 s
        // before the first Activity: it is the gap since init that says the process was started to show something.
        now = 10_000
        val t = AppStartTracker(5_000L, true, { now }, hook, { starts += it }, { dones += it }).also {
            app.registerActivityLifecycleCallbacks(it)
            registered = it
        }
        now = 10_300
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now = 10_800
        hook.requests.single().onFrame()

        assertOneStart(5_800, "PlainActivity")
        assertTrue(t.isDone)
    }

    @Test
    fun aSplashDestroyedAndTheNextActivityCreatedMuchLater_emitsNothing() {
        // A trampoline that finishes in onCreate without starting an Activity of this process: the user opens the app
        // 20 s later. That is not the start of the process.
        val t = tracker()
        now = 10_100
        val splash = Robolectric.buildActivity(SplashActivity::class.java).create()
        splash.get().finish()
        splash.destroy()
        now = 30_000
        Robolectric.buildActivity(PlainActivity::class.java).setup()

        assertTrue(starts.isEmpty())
        assertTrue(t.isDone)
        assertTrue(hook.requests.isEmpty())
    }

    @Test
    fun aPausedCandidateAndAnActivityOpenedMuchLater_emitsNothing() {
        // A system dialog over a splash: the Activity pauses before it drew and resumes later without being created again.
        val t = tracker()
        now = 10_100
        val first = Robolectric.buildActivity(PlainActivity::class.java).setup()
        first.pause()
        now = 30_000
        Robolectric.buildActivity(AnnotatedActivity::class.java).setup()

        assertTrue(starts.isEmpty())
        assertTrue(t.isDone)
        assertEquals("only the first Activity was ever waited for", 1, hook.requests.size)
    }

    @Test
    fun theNextActivityIsMeasuredWithinTheWindow_butNotAfterIt() {
        val atTheEdge = tracker()
        now = 10_100
        val splash = Robolectric.buildActivity(SplashActivity::class.java).create()
        splash.get().finish()
        splash.destroy()
        now = 10_100 + AppStartTracker.MAX_NEXT_CREATE_DELAY_MS
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        assertFalse(atTheEdge.isDone)
        assertEquals(1, hook.requests.size)
    }

    @Test
    fun theNextActivityOneMsPastTheWindow_isNotMeasured() {
        val t = tracker()
        now = 10_100
        val splash = Robolectric.buildActivity(SplashActivity::class.java).create()
        splash.get().finish()
        splash.destroy()
        now = 10_100 + AppStartTracker.MAX_NEXT_CREATE_DELAY_MS + 1
        Robolectric.buildActivity(PlainActivity::class.java).setup()

        assertTrue(t.isDone)
        assertTrue(hook.requests.isEmpty())
    }

    @Test
    fun pausedCandidateCancelsItsHook() {
        tracker()
        now = 10_100
        val controller = Robolectric.buildActivity(PlainActivity::class.java).setup()
        val first = hook.requests.single()
        controller.pause()

        assertEquals(1, hook.cancels)
        now = 10_500
        first.onFrame() // a stale callback is ignored
        assertTrue(starts.isEmpty())
    }

    @Test
    fun backgroundBeforeFirstFrameEmitsNothing() {
        val t = tracker()
        now = 10_100
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        t.onAppBackground()
        now = 10_900
        hook.requests.single().onFrame()
        Robolectric.buildActivity(AnnotatedActivity::class.java).setup()

        assertTrue(starts.isEmpty())
        assertEquals(1, dones.size)
    }

    @Test
    fun onlyOneAppStartPerProcess() {
        tracker()
        now = 10_100
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now = 10_300
        hook.requests.single().onFrame()
        Robolectric.buildActivity(AnnotatedActivity::class.java).setup()

        assertEquals(1, starts.size)
        assertEquals("no second request after the first frame", 1, hook.requests.size)
    }

    @Test
    fun implausiblyLongDurationEmitsNothing() {
        val t = tracker()
        now = 10_100
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now = processStart + AppStartTracker.MAX_DURATION_MS + 1
        hook.requests.single().onFrame()

        assertTrue(starts.isEmpty())
        assertTrue(t.isDone)
    }

    @Test
    fun throwingOnAppStartDoesNotEscape() {
        val t = tracker(onAppStart = { error("boom") })
        now = 10_100
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now = 10_300
        hook.requests.single().onFrame() // must not throw

        assertTrue(t.isDone)
        assertFalse(starts.isNotEmpty())
    }

    // --- phases, trampoline, drop reasons ---

    @Test
    fun coldStart_reportsFourPhasesSummingToTheDuration() {
        now = 10_300
        val t = tracker()
        val activity = Robolectric.buildActivity(PlainActivity::class.java).get()
        now = 11_800
        t.onActivityPreCreated(activity, null)
        now = 11_850
        t.onActivityCreated(activity, null)
        now = 12_180
        t.onActivityResumed(activity)
        now = 12_400
        hook.requests.single().onFrame()

        val s = starts.single()
        assertEquals(
            listOf(Phase("process_to_init", 300), Phase("application_on_create", 1500), Phase("activity_create", 380), Phase("first_draw", 220)),
            s.phases,
        )
        assertEquals(2_400L, s.durationMs)
        assertEquals(StartType.COLD, s.type)
        assertFalse(s.trampoline)
        assertEquals(10_000L, s.anchorUptimeMs)
        assertEquals(listOf<ColdStartDrop?>(null), dones)
    }

    @Test
    fun withoutPreCreated_theCreatedTimeIsTheBoundary() {
        now = 10_300
        val t = tracker()
        val activity = Robolectric.buildActivity(PlainActivity::class.java).get()
        now = 11_850
        t.onActivityCreated(activity, null)
        now = 12_180
        t.onActivityResumed(activity)
        now = 12_400
        hook.requests.single().onFrame()

        val s = starts.single()
        assertEquals(1_550L, s.phases.first { it.name == "application_on_create" }.ms)
        assertEquals(330L, s.phases.first { it.name == "activity_create" }.ms) // created 11 850 -> resumed 12 180
    }

    @Test
    fun trampoline_measuresActivityCreateFromTheFirstActivity() {
        now = 10_300
        val t = tracker()
        val splash = Robolectric.buildActivity(SplashActivity::class.java).get()
        val main = Robolectric.buildActivity(PlainActivity::class.java).get()
        now = 11_800
        t.onActivityPreCreated(splash, null)
        t.onActivityCreated(splash, null)
        t.onActivityPaused(splash)
        now = 12_000
        t.onActivityCreated(main, null)
        now = 12_100
        t.onActivityResumed(main)
        now = 12_600
        hook.requests.single().onFrame()

        val s = starts.single()
        assertTrue(s.trampoline)
        assertEquals("PlainActivity", s.firstScreen)
        assertEquals(1_500L, s.phases.first { it.name == "application_on_create" }.ms)
        assertEquals(300L, s.phases.first { it.name == "activity_create" }.ms) // 11 800 -> resumed 12 100
        assertEquals(2_600L, s.phases.sumOf { it.ms })
    }

    @Test
    fun aReportedStart_callsOnDoneWithNull() {
        tracker()
        now = 10_100
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now = 10_400
        hook.requests.single().onFrame()

        assertEquals(listOf<ColdStartDrop?>(null), dones)
    }

    @Test
    fun drop_notForegroundAtInit() {
        tracker(startedInForeground = false)
        now = 10_100
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        assertEquals(listOf<ColdStartDrop?>(ColdStartDrop.NOT_FOREGROUND_AT_INIT), dones)
    }

    @Test
    fun drop_lateFirstActivity() {
        tracker()
        now = processStart + AppStartTracker.MAX_FIRST_CREATE_DELAY_MS + 1
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        assertEquals(listOf<ColdStartDrop?>(ColdStartDrop.LATE_FIRST_ACTIVITY), dones)
    }

    @Test
    fun drop_initAfterFirstActivity() {
        val t = tracker()
        t.onActivityResumed(Robolectric.buildActivity(PlainActivity::class.java).get())
        assertEquals(listOf<ColdStartDrop?>(ColdStartDrop.INIT_AFTER_FIRST_ACTIVITY), dones)
    }

    @Test
    fun drop_nextActivityTooLate() {
        tracker()
        now = 10_100
        val splash = Robolectric.buildActivity(SplashActivity::class.java).create()
        splash.get().finish()
        splash.destroy()
        now = 10_100 + AppStartTracker.MAX_NEXT_CREATE_DELAY_MS + 1
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        assertEquals(listOf<ColdStartDrop?>(ColdStartDrop.NEXT_ACTIVITY_TOO_LATE), dones)
    }

    @Test
    fun drop_backgroundBeforeFrame() {
        val t = tracker()
        now = 10_100
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        t.onAppBackground()
        assertEquals(listOf<ColdStartDrop?>(ColdStartDrop.BACKGROUND_BEFORE_FRAME), dones)
    }

    @Test
    fun drop_outOfRange() {
        tracker()
        now = 10_100
        Robolectric.buildActivity(PlainActivity::class.java).setup()
        now = processStart + AppStartTracker.MAX_DURATION_MS + 1
        hook.requests.single().onFrame()
        assertEquals(listOf<ColdStartDrop?>(ColdStartDrop.OUT_OF_RANGE), dones)
    }
}
