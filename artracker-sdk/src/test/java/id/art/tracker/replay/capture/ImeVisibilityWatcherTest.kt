package id.art.tracker.replay.capture

import android.app.Activity
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeVisibilityWatcherTest {
    private var visible: Boolean? = false
    private val changes = ArrayList<Boolean>()

    private fun decor(): View = Robolectric.buildActivity(Activity::class.java).setup().get().window.decorView

    private fun layout(view: View) {
        val m = ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnGlobalLayout")
        m.isAccessible = true
        m.invoke(view.viewTreeObserver)
    }

    private fun watcher() = ImeVisibilityWatcher({ visible }) { changes += it }

    @Test
    fun firesOnlyWhenTheVisibilityChanges() {
        val view = decor()
        val w = watcher()
        w.attach(view)
        layout(view)
        layout(view)
        assertEquals(emptyList<Boolean>(), changes) // layout passes alone are not changes

        visible = true
        layout(view)
        layout(view)
        visible = false
        layout(view)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun unknownVisibility_isIgnored_andInitialStateIsNotAChange() {
        val view = decor()
        visible = true
        val w = watcher()
        w.attach(view) // already shown when we start watching: not a change
        visible = null
        layout(view)
        visible = true
        layout(view)
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test
    fun detach_stopsListening_andAttachIsIdempotent() {
        val view = decor()
        val w = watcher()
        w.attach(view)
        w.attach(view)
        w.detach(view)
        visible = true
        layout(view)
        assertEquals(emptyList<Boolean>(), changes)

        w.attach(view)
        w.detachAll()
        layout(view)
        assertEquals(emptyList<Boolean>(), changes)
    }
}
