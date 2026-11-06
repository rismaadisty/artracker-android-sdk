package id.art.tracker.sample

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Independent of `local.properties` (the real [SampleApplication] reads `BuildConfig.ARTRACKER_KEY`, so it is replaced
 * by [TestApplication], which never initialises the SDK) and of test order.
 *
 * Everything runs at ONE Robolectric SDK level: two levels in one JVM start a second sandbox, which collides in
 * Robolectric's native runtime loader (`FileSystemAlreadyExistsException`).
 *
 * The SDK is a process-wide singleton and its reset hook is internal to `:artracker-sdk`, so only
 * [initTrackerWithKeyInitialisesTheSdk] touches it. The blank-key tests assert the return value of
 * [SampleApp.initTracker] only: with a blank key it returns before reaching `ARTracker.init`, whether or not another
 * test already initialised the SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SampleAppTest.TestApplication::class)
class SampleAppTest {
    /** Plain application: unlike [SampleApplication] it does not call `SampleApp.initTracker`. */
    class TestApplication : Application()

    @Test
    fun initTrackerWithEmptyKeyReturnsFalse() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(SampleApp.initTracker(app, endpoint = "http://10.0.2.2:3000", key = ""))
    }

    @Test
    fun initTrackerWithBlankKeyReturnsFalse() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(SampleApp.initTracker(app, endpoint = "", key = "   "))
    }

    @Test
    fun initTrackerWithKeyInitialisesTheSdk() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ok = SampleApp.initTracker(app, endpoint = "http://10.0.2.2:3000", key = "art_live_" + "a".repeat(43))
        assertTrue(ok)
        assertTrue(ARTracker.isInitialized())
    }
}
