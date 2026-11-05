package id.art.tracker.lifecycle

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.testutil.ScreenCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class PlainActivity : Activity()

@ARTrackerScreenName("Checkout Flow")
class AnnotatedActivity : Activity()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityTrackerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val capture = ScreenCapture()
    private val events = ArrayList<String>()
    private val tracker = ActivityTracker(
        capture.tracker,
        object : ActivityTracker.WindowListener {
            override fun onActivityResumed(a: Activity) {
                events += "resumed:${a.javaClass.simpleName}"
            }

            override fun onActivityPaused(a: Activity) {
                events += "paused:${a.javaClass.simpleName}"
            }
        },
    )

    @Before
    fun setUp() = app.registerActivityLifecycleCallbacks(tracker)

    @After
    fun tearDown() = app.unregisterActivityLifecycleCallbacks(tracker)

    @Test
    fun resumedActivity_emitsScreenView() {
        Robolectric.buildActivity(PlainActivity::class.java).setup()

        assertEquals(listOf("PlainActivity:activity"), capture.screens())
        assertEquals(listOf("resumed:PlainActivity"), events)
    }

    @Test
    fun annotation_overridesName() {
        Robolectric.buildActivity(AnnotatedActivity::class.java).setup()

        assertEquals(listOf("Checkout Flow:activity"), capture.screens())
    }

    @Test
    fun secondActivity_reportsPrevious_andPauseReachesListener() {
        val first = Robolectric.buildActivity(PlainActivity::class.java).setup()
        first.pause()
        Robolectric.buildActivity(AnnotatedActivity::class.java).setup()

        assertEquals(listOf("PlainActivity:activity", "Checkout Flow:activity"), capture.screens())
        assertEquals("PlainActivity", capture.emitted[1].attributes["previous"])
        assertEquals(
            listOf("resumed:PlainActivity", "paused:PlainActivity", "resumed:AnnotatedActivity"),
            events,
        )
    }

    @Test
    fun nullListener_isFine() {
        app.unregisterActivityLifecycleCallbacks(tracker)
        val plain = ActivityTracker(capture.tracker, null)
        app.registerActivityLifecycleCallbacks(plain)
        try {
            Robolectric.buildActivity(PlainActivity::class.java).setup().pause()
        } finally {
            app.unregisterActivityLifecycleCallbacks(plain)
        }
        assertEquals(listOf("PlainActivity:activity"), capture.screens())
    }
}
