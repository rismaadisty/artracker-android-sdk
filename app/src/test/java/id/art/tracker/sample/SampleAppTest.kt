package id.art.tracker.sample

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.art.tracker.ARTracker
import id.art.tracker.playground.settings.EndpointGuard
import id.art.tracker.playground.settings.PlaygroundSettings
import org.junit.Assert.assertEquals
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
        assertFalse(SampleApp.initTracker(app, endpoint = "http://sample.sdk-test.invalid:3000", key = "", debug = false))
    }

    @Test
    fun initTrackerWithBlankKeyReturnsFalse() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(SampleApp.initTracker(app, endpoint = "", key = "   ", debug = false))
    }

    @Test
    fun configCarriesTheDebugFlagItIsGiven_neverAHardCodedTrue() {
        val key = "art_live_" + "a".repeat(43)
        assertFalse(SampleApp.config("http://sample.sdk-test.invalid:3000", key, debug = false).debug)
        assertTrue(SampleApp.config("http://sample.sdk-test.invalid:3000", key, debug = true).debug)
    }

    @Test
    fun initTrackerWithBlankKeyStillReturnsFalseWithSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(SampleApp.initTracker(app, endpoint = "http://x.invalid", key = "", debug = false, settings = PlaygroundSettings.DEFAULT))
    }

    @Test
    fun configWithoutSettingsPassesTheEndpointThrough() {
        val key = "art_live_" + "a".repeat(43)
        assertEquals("https://b.sdk-test.invalid", SampleApp.config("https://b.sdk-test.invalid", key, debug = false).endpoint)
    }

    @Test
    fun configWithSettingsUsesTheResolvedEndpointArgument() {
        val c = SampleApp.config("http://localhost:1", "art_live_" + "a".repeat(43), debug = false, settings = PlaygroundSettings.DEFAULT)
        assertEquals("http://localhost:1", c.endpoint)
        assertEquals("sample", c.environment)
    }

    @Test
    fun dummyKeyAndSinkEndpointAreCarriedIntoTheConfigUnchanged() {
        val c = SampleApp.config(EndpointGuard.SINK_ENDPOINT, EndpointGuard.DUMMY_KEY, debug = false, settings = PlaygroundSettings.DEFAULT)
        assertEquals(EndpointGuard.SINK_ENDPOINT, c.endpoint)
        assertEquals(EndpointGuard.DUMMY_KEY, c.apiKey)
    }

    @Test
    fun initTrackerWithKeyInitialisesTheSdk() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ok = SampleApp.initTracker(app, endpoint = "http://sample.sdk-test.invalid:3000", key = "art_live_" + "a".repeat(43), debug = false)
        assertTrue(ok)
        assertTrue(ARTracker.isInitialized())
    }

    @Test
    fun theSampleRecordsScreenshotReplay_withAndWithoutSettings() {
        val key = "art_live_" + "a".repeat(43)
        val legacy = SampleApp.config("http://sample.sdk-test.invalid:3000", key, debug = false).replay
        assertEquals(id.art.tracker.ReplayMode.SCREENSHOT, legacy.mode)
        assertEquals(2.0, legacy.framesPerSecond, 0.0)
        assertTrue(legacy.enabled)
        val fromSettings = SampleApp.config("http://localhost:1", key, debug = false, settings = PlaygroundSettings.DEFAULT).replay
        assertEquals(id.art.tracker.ReplayMode.SCREENSHOT, fromSettings.mode)
    }
}
