package id.art.tracker.lifecycle

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real first-frame hook: an OnDrawListener on the decor view, then a post once the traversal is done. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecorViewFirstFrameTest {
    /** `ViewTreeObserver.dispatchOnDraw` is hidden: what the framework calls for every frame. */
    private fun dispatchOnDraw(activity: Activity) {
        val m = ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnDraw")
        m.isAccessible = true
        m.invoke(activity.window.decorView.viewTreeObserver)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun firesOnceAfterTheFirstDraw() {
        val a = Robolectric.buildActivity(PlainActivity::class.java).setup().get()
        var n = 0
        DecorViewFirstFrame.await(a) { n++ }

        dispatchOnDraw(a)
        dispatchOnDraw(a)
        idle()

        assertEquals(1, n)
    }

    @Test
    fun doesNotFireBeforeTheDrawIsProcessed() {
        val a = Robolectric.buildActivity(PlainActivity::class.java).setup().get()
        var n = 0
        DecorViewFirstFrame.await(a) { n++ }

        dispatchOnDraw(a) // the callback is posted to the front of the queue: it runs after the traversal

        assertEquals(0, n)
        idle()
        assertEquals(1, n)
    }

    @Test
    fun cancelBeforeDrawNeverFires() {
        val a = Robolectric.buildActivity(PlainActivity::class.java).setup().get()
        var n = 0
        val cancel = DecorViewFirstFrame.await(a) { n++ }

        cancel()
        dispatchOnDraw(a)
        idle()

        assertEquals(0, n)
    }

    @Test
    fun cancelAfterTheDrawButBeforeThePostNeverFires() {
        val a = Robolectric.buildActivity(PlainActivity::class.java).setup().get()
        var n = 0
        val cancel = DecorViewFirstFrame.await(a) { n++ }

        dispatchOnDraw(a)
        cancel()
        idle()

        assertEquals(0, n)
    }

    @Test
    fun waitsForAttachWhenTheDecorIsDetached() {
        // Below API 26 a view that is not attached yet loses the listeners of its ViewTreeObserver at attach time; the
        // hook waits for the attach on every API level (cached Robolectric SDKs do not include 24).
        val controller = Robolectric.buildActivity(PlainActivity::class.java).create()
        controller.get().setContentView(View(controller.get()))
        controller.start().resume()
        val a = controller.get()
        assertFalse("the decor is not attached before the window becomes visible", a.window.decorView.isAttachedToWindow)
        var n = 0
        DecorViewFirstFrame.await(a) { n++ }

        controller.visible()
        dispatchOnDraw(a)
        idle()

        assertEquals(1, n)
    }

    @Test
    fun anActivityWithoutADecorYetIsNotForcedToCreateOne() {
        val a = Robolectric.buildActivity(PlainActivity::class.java).create().get()
        var n = 0
        val cancel = DecorViewFirstFrame.await(a) { n++ }
        cancel() // a no-op, and must not throw

        assertEquals(0, n)
    }
}
