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
    private var done = 0

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
        onDone = { done++ },
    ).also {
        app.registerActivityLifecycleCallbacks(it)
        registered = it
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

        assertEquals(listOf(AppStart(850, "PlainActivity")), starts)
        assertTrue(t.isDone)
        assertEquals(1, done)
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
        now = processStart + 1_001
        Robolectric.buildActivity(PlainActivity::class.java).setup()

        assertTrue(starts.isEmpty())
        assertTrue(t.isDone)
        assertTrue("the hook is never asked", hook.requests.isEmpty())
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

        assertEquals(listOf(AppStart(700, "PlainActivity")), starts)
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
        assertEquals(1, done)
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
}
