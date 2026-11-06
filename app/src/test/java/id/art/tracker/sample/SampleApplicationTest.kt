package id.art.tracker.sample

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * The SDK is a process-wide singleton and its reset hook is internal, so the "initialised" and "blank key"
 * cases live in two classes that run on different Robolectric SDK levels, i.e. in separate sandboxes with
 * separate static state.
 */

/** Blank key: the sample app must start, skip the SDK and never throw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleApplicationBlankKeyTest {
    @Test
    fun applicationStartsWithoutInitialisingTheSdkWhenKeyIsBlank() {
        assumeTrue("needs a build without artracker.key in local.properties", BuildConfig.ARTRACKER_KEY.isBlank())
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The manifest Application is SampleApplication; its onCreate already ran (BuildConfig key is blank in tests).
        assertTrue(app is SampleApplication)
        assertFalse(ARTracker.isInitialized())
    }

    @Test
    fun initTrackerWithBlankKeyReturnsFalseAndLeavesSdkUninitialised() {
        assumeTrue("needs a build without artracker.key in local.properties", BuildConfig.ARTRACKER_KEY.isBlank())
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(SampleApp.initTracker(app, endpoint = "http://10.0.2.2:3000", key = ""))
        assertFalse(SampleApp.initTracker(app, endpoint = "http://10.0.2.2:3000", key = "   "))
        assertFalse(ARTracker.isInitialized())
    }
}

/** Non-blank key: the SDK gets initialised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SampleApplicationInitTest {
    @Test
    fun initTrackerWithKeyInitialisesTheSdk() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ok = SampleApp.initTracker(app, endpoint = "http://10.0.2.2:3000", key = "art_live_" + "a".repeat(43))
        assertTrue(ok)
        assertTrue(ARTracker.isInitialized())
    }
}
