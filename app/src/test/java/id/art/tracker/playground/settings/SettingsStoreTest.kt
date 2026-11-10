package id.art.tracker.playground.settings

import android.app.Application
import android.content.Context
import id.art.tracker.ReplayQuality
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One Robolectric SDK level for the whole module (two levels in one JVM collide, see SampleAppTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("playground_settings", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        prefs.edit().clear().commit()
    }

    @Test
    fun roundTripsEveryField() {
        val s = PlaygroundSettings(
            endpointProfile = EndpointProfile.OFFLINE,
            localEndpoint = "http://127.0.0.1:4000",
            environment = "qa",
            replayEnabled = false,
            replaySampleRate = 0.25,
            replayFps = 2.5,
            replayQuality = ReplayQuality.HIGH,
            maskAllInputs = false,
            maskAllText = true,
            networkSampleRate = 0.5,
            captureOnlyErrors = true,
            ignoreHosts = setOf("a.com", "b.com"),
            flushIntervalMs = 2_000,
            sessionTimeoutMs = 60_000,
        )
        val store = SharedPrefsSettingsStore(context)
        store.save(s)
        assertEquals(s, SharedPrefsSettingsStore(context).load())
    }

    @Test
    fun emptyStoreLoadsDefault() {
        val loaded = SharedPrefsSettingsStore(context).load()
        assertEquals(PlaygroundSettings.DEFAULT, loaded)
        assertEquals(EndpointProfile.SINK, loaded.endpointProfile)
    }

    @Test
    fun corruptValuesFallBackToDefault() {
        prefs.edit().putString("replaySampleRate", "not-a-number").putString("endpointProfile", "NOPE").commit()
        assertEquals(PlaygroundSettings.DEFAULT, SharedPrefsSettingsStore(context).load())
        prefs.edit().clear().putString("flushIntervalMs", "x").commit()
        assertEquals(PlaygroundSettings.DEFAULT, SharedPrefsSettingsStore(context).load())
    }

    @Test
    fun loadNeverReturnsInvalidSettings() {
        val store = SharedPrefsSettingsStore(context)
        store.save(PlaygroundSettings.DEFAULT.copy(flushIntervalMs = 5))
        assertEquals(PlaygroundSettings.DEFAULT, SharedPrefsSettingsStore(context).load())
    }

    @Test
    fun loadResultSaysWhenSavedSettingsWereDiscarded() {
        val store = SharedPrefsSettingsStore(context)
        assertEquals(false, store.loadResult().discarded) // nothing saved yet is not "discarded"
        store.save(PlaygroundSettings.DEFAULT.copy(environment = "qa"))
        assertEquals(false, store.loadResult().discarded)
        assertEquals("qa", store.loadResult().settings.environment)

        prefs.edit().putString("replaySampleRate", "not-a-number").commit()
        assertEquals(true, store.loadResult().discarded)
        assertEquals(PlaygroundSettings.DEFAULT, store.loadResult().settings)

        prefs.edit().clear().commit()
        store.save(PlaygroundSettings.DEFAULT.copy(flushIntervalMs = 5))
        assertEquals(true, store.loadResult().discarded)
    }

    @Test
    fun anOldSavedStateWithoutTheQualityKey_loadsTheDefault_andAStaleModeKeyIsIgnored() {
        SharedPrefsSettingsStore(context).save(PlaygroundSettings.DEFAULT)
        prefs.edit().remove("replayQuality").putString("replayMode", "WIREFRAME").commit() // a state saved before modes went away
        val result = SharedPrefsSettingsStore(context).loadResult()
        assertEquals(false, result.discarded)
        assertEquals(ReplayQuality.STANDARD, result.settings.replayQuality)
    }
}
