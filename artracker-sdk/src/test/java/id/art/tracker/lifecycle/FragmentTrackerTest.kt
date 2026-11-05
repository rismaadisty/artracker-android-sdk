package id.art.tracker.lifecycle

import android.app.Application
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.core.ClassAvailability
import id.art.tracker.testutil.ScreenCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class HostActivity : FragmentActivity()

class ListFragment : Fragment()

@ARTrackerScreenName("Detail Page")
class DetailFragment : Fragment()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FragmentTrackerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val capture = ScreenCapture()
    private val tracker = ActivityTracker(capture.tracker, null)

    @Before
    fun setUp() = app.registerActivityLifecycleCallbacks(tracker)

    @After
    fun tearDown() {
        app.unregisterActivityLifecycleCallbacks(tracker)
        ClassAvailability.resetOverrides()
    }

    @Test
    fun fragmentResume_emitsFragmentScreen() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val fm = controller.get().supportFragmentManager
        fm.beginTransaction().add(ListFragment(), "list").commitNow()

        assertEquals(listOf("HostActivity:activity", "ListFragment:fragment"), capture.screens())
        assertEquals("HostActivity", capture.emitted[1].attributes["previous"])
    }

    @Test
    fun nestedFragmentAndAnnotation_areTracked_recursively() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val parent = ListFragment()
        controller.get().supportFragmentManager.beginTransaction().add(parent, "list").commitNow()
        parent.childFragmentManager.beginTransaction().add(DetailFragment(), "detail").commitNow()

        assertEquals(
            listOf("HostActivity:activity", "ListFragment:fragment", "Detail Page:fragment"),
            capture.screens(),
        )
    }

    @Test
    fun detachAll_stopsFragmentTracking() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        tracker.detachAll()
        controller.get().supportFragmentManager.beginTransaction().add(ListFragment(), "list").commitNow()

        assertEquals(listOf("HostActivity:activity"), capture.screens())
    }

    @Test
    fun fragmentUnavailable_neverEntersFragmentSupport() {
        ClassAvailability.overrides["androidx.fragment.app.FragmentActivity"] = false
        val before = FragmentSupport.registerCalls.get()
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction().add(ListFragment(), "list").commitNow()

        assertEquals(before, FragmentSupport.registerCalls.get())
        assertEquals(listOf("HostActivity:activity"), capture.screens())
    }
}
