package id.art.tracker.lifecycle

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The decisions of the warm and hot start measurement, without a real first frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WarmHotStartTrackerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 0L
    private val starts = ArrayList<AppStart>()

    private class FakeHook : FirstFrameHook {
        val requests = ArrayList<Pair<Activity, () -> Unit>>()
        var cancels = 0

        override fun await(activity: Activity, onFirstFrame: () -> Unit): () -> Unit {
            requests += activity to onFirstFrame
            return { cancels++ }
        }
    }

    private val hook = FakeHook()
    private var registered: WarmHotStartTracker? = null

    private fun tracker(): WarmHotStartTracker =
        WarmHotStartTracker(uptime = { now }, firstFrame = hook, onAppStart = { starts += it })

    private fun registeredTracker(): WarmHotStartTracker = tracker().also {
        app.registerActivityLifecycleCallbacks(it)
        registered = it
    }

    private fun activity(): Activity = Robolectric.buildActivity(PlainActivity::class.java).get()

    @After
    fun tearDown() {
        registered?.let(app::unregisterActivityLifecycleCallbacks)
    }

    @Test
    fun unarmedTracker_reportsNothing() {
        val t = tracker()
        val a = activity()
        now = 100
        t.onActivityPreCreated(a, null)
        t.onActivityCreated(a, null)
        t.onActivityStarted(a)
        now = 200
        t.onActivityResumed(a)

        assertTrue(hook.requests.isEmpty())
        assertTrue(starts.isEmpty())
    }

    @Test
    fun armedThenCreated_isWarm() {
        val t = tracker()
        t.arm()
        val a = activity()
        now = 1_000
        t.onActivityPreCreated(a, null)
        now = 1_050
        t.onActivityCreated(a, null)
        t.onActivityStarted(a)
        now = 1_300
        t.onActivityResumed(a)
        now = 1_450
        hook.requests.single().second()

        val s = starts.single()
        assertEquals(StartType.WARM, s.type)
        assertEquals(450L, s.durationMs)
        assertEquals(listOf(Phase("activity_create", 300), Phase("first_draw", 150)), s.phases)
        assertEquals("PlainActivity", s.firstScreen)
        assertEquals(1_000L, s.anchorUptimeMs)
        assertEquals(false, s.trampoline)
    }

    @Test
    fun armedThenStartedWithoutCreate_isHot() {
        val t = tracker()
        t.arm()
        val a = activity()
        now = 2_000
        t.onActivityStarted(a)
        now = 2_080
        t.onActivityResumed(a)
        now = 2_140
        hook.requests.single().second()

        val s = starts.single()
        assertEquals(StartType.HOT, s.type)
        assertEquals(140L, s.durationMs)
        assertEquals(listOf(Phase("activity_start", 80), Phase("first_draw", 60)), s.phases)
        assertEquals(2_000L, s.anchorUptimeMs)
    }

    @Test
    fun onlyOneStartPerArming() {
        val t = tracker()
        t.arm()
        val a = activity()
        now = 100
        t.onActivityStarted(a)
        now = 150
        t.onActivityResumed(a)
        now = 200
        hook.requests.single().second()
        // The same Activity goes on: stopped, started and resumed again, with no new arm().
        now = 5_000
        t.onActivityStarted(a)
        t.onActivityResumed(a)

        assertEquals(1, starts.size)
        assertEquals(1, hook.requests.size)
    }

    @Test
    fun backgroundBeforeFrame_dropsAndReArms() {
        val t = tracker()
        t.arm()
        val a = activity()
        now = 1_000
        t.onActivityStarted(a)
        now = 1_050
        t.onActivityResumed(a)
        t.onAppBackground()
        assertEquals("the pending frame wait is cancelled", 1, hook.cancels)
        now = 3_000
        t.onActivityStarted(a)
        now = 3_040
        t.onActivityResumed(a)
        now = 3_100
        hook.requests.last().second()

        assertEquals(1, starts.size)
        assertEquals(StartType.HOT, starts.single().type)
        assertEquals(100L, starts.single().durationMs)
        assertEquals(3_000L, starts.single().anchorUptimeMs)
    }

    @Test
    fun over10s_isDropped() {
        val t = tracker()
        t.arm()
        val a = activity()
        now = 1_000
        t.onActivityStarted(a)
        now = 1_100
        t.onActivityResumed(a)
        now = 1_000 + WarmHotStartTracker.MAX_WARM_HOT_MS + 1
        hook.requests.single().second()

        assertTrue(starts.isEmpty())
        // dropped means disarmed: a further start of the same kind is not measured until armed again
        t.onActivityStarted(a)
        t.onActivityResumed(a)
        assertEquals(1, hook.requests.size)
    }

    @Test
    fun exactly10s_isReported() {
        val t = tracker()
        t.arm()
        val a = activity()
        now = 1_000
        t.onActivityStarted(a)
        now = 1_100
        t.onActivityResumed(a)
        now = 1_000 + WarmHotStartTracker.MAX_WARM_HOT_MS
        hook.requests.single().second()

        assertEquals(WarmHotStartTracker.MAX_WARM_HOT_MS, starts.single().durationMs)
    }

    @Test
    fun rotationBeforeFrame_keepsTheAnchor() {
        val t = tracker()
        t.arm()
        val first = activity()
        now = 1_000
        t.onActivityCreated(first, null)
        t.onActivityDestroyed(first)
        val second = activity()
        now = 1_200
        t.onActivityCreated(second, null)
        now = 1_300
        t.onActivityResumed(second)
        now = 1_400
        hook.requests.single().second()

        val s = starts.single()
        assertEquals(StartType.WARM, s.type)
        assertEquals(400L, s.durationMs)
        assertEquals(1_000L, s.anchorUptimeMs)
        assertEquals(400L, s.phases.sumOf { it.ms })
    }

    @Test
    fun rotationBeforeResume_doesNotWaitForTheDestroyedActivity() {
        val t = tracker()
        t.arm()
        val first = activity()
        now = 1_000
        t.onActivityCreated(first, null)
        t.onActivityResumed(first)
        t.onActivityDestroyed(first)

        assertEquals("the wait for the destroyed Activity is cancelled", 1, hook.cancels)
    }

    @Test
    fun aStart_throwingOnAppStart_doesNotEscape() {
        val t = WarmHotStartTracker({ now }, hook) { error("boom") }
        t.arm()
        val a = activity()
        now = 10
        t.onActivityStarted(a)
        t.onActivityResumed(a)
        now = 20
        hook.requests.single().second() // must not throw
    }

    @Test
    fun notArmed_meansNoStart_evenWhenActivitiesAreAliveAtRegistration() {
        // Review Focus 1: init ran late, an Activity was already started. No warm or hot start may be reported until the
        // core arms the tracker (after the app has been in the background once).
        val controller = Robolectric.buildActivity(PlainActivity::class.java).create().start().resume()
        registeredTracker()
        controller.pause().stop()
        now = 100
        controller.start().resume()

        assertTrue(hook.requests.isEmpty())
        assertTrue(starts.isEmpty())
    }
}
