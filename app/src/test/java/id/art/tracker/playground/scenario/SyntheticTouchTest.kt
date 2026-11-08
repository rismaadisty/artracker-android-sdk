package id.art.tracker.playground.scenario

import android.app.Application
import android.graphics.PointF
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The synthetic tap must travel the framework's own path: `decorView.dispatchTouchEvent` -> `window.callback` (where the
 * SDK's TouchRecorder sits while replay records) -> the view tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyntheticTouchTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Capturing(val delegate: Window.Callback) : Window.Callback by delegate {
        val events = mutableListOf<MotionEvent>()
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event != null) events += MotionEvent.obtain(event)
            return delegate.dispatchTouchEvent(event)
        }
    }

    @Test
    fun aTapReachesTheWindowCallbackWithScreenAndWindowCoordinatesAndClicksTheTarget() {
        var clicks = 0
        rule.setContent { Box(Modifier.size(100.dp).tapTarget("t").clickable { clicks++ }) }
        rule.waitForIdle()
        val target = TapRegistry.Shared.find("t")!!
        assertTrue("the target must be recognised as living in the activity window", target.inActivityWindow)

        val capture = Capturing(rule.activity.window.callback)
        rule.activity.window.callback = capture
        val center = PointF(target.boundsInWindow.center.x, target.boundsInWindow.center.y)

        runBlocking { SyntheticTouch.tap(rule.activity, center, windowOrigin = PointF(0f, 48f), pause = {}) }
        rule.waitForIdle()

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), capture.events.map { it.actionMasked })
        capture.events.forEach {
            assertEquals(center.x, it.x, 0.5f)
            assertEquals(center.y, it.y, 0.5f)
            assertEquals(center.x, it.rawX, 0.5f)
            assertEquals(center.y + 48f, it.rawY, 0.5f)
            assertEquals(InputDevice.SOURCE_TOUCHSCREEN, it.source and InputDevice.SOURCE_TOUCHSCREEN)
        }
        assertEquals(SyntheticTouchPlanner.TAP_MS, capture.events[1].eventTime - capture.events[0].eventTime)
        assertEquals(1, clicks)
    }

    @Test
    fun aPlainActivityHasNoSdkRecorderInstalled() {
        assertFalse(SyntheticTouch.isRecorderInstalled(rule.activity))
    }
}
