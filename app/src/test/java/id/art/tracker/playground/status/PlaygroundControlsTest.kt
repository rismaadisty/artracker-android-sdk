package id.art.tracker.playground.status

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The host and the Replay lab must share ONE SdkControls, or their replay intent would drift apart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaygroundControlsTest {
    @Test
    fun getReturnsTheSameInstanceEveryTime() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertSame(PlaygroundControls.get(context), PlaygroundControls.get(context))
    }
}
