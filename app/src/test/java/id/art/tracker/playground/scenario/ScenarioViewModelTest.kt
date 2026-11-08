package id.art.tracker.playground.scenario

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ViewModelStore survives a configuration change, so a scenario run (and its Lanjut gate) survives a rotation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScenarioViewModelTest {
    @Test
    fun theSameStoreReturnsTheSameHostAfterAConfigurationChange() {
        val store = ViewModelStore()
        val first = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ScenarioViewModel::class.java]
        val afterRotation = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ScenarioViewModel::class.java]
        assertSame(first, afterRotation)
        assertSame(first.host, afterRotation.host)
    }
}
