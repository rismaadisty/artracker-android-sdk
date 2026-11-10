package id.art.tracker.replay.screenshot

import android.app.Application
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowFactsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun rootWith(flags: Int?): View = FrameLayout(context).also {
        if (flags != null) it.layoutParams = WindowManager.LayoutParams().apply { this.flags = flags }
    }

    @Test
    fun flagSecure_isDetectedOnTheWindowLayoutParams() {
        assertTrue(WindowFacts.isSecure(rootWith(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_DIM_BEHIND)))
        assertFalse(WindowFacts.isSecure(rootWith(WindowManager.LayoutParams.FLAG_DIM_BEHIND)))
        assertFalse(WindowFacts.isSecure(rootWith(null)))
    }

    @Test
    fun dimAmount_isReadOnlyWhenTheWindowDimsBehind() {
        val dimmed = FrameLayout(context).apply {
            layoutParams = WindowManager.LayoutParams().apply { flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND; dimAmount = 0.6f }
        }
        assertEquals(0.6f, WindowFacts.dimAmount(dimmed), 0f)
        val notDimmed = FrameLayout(context).apply {
            layoutParams = WindowManager.LayoutParams().apply { dimAmount = 0.6f }
        }
        assertEquals(0f, WindowFacts.dimAmount(notDimmed), 0f)
        assertEquals(0f, WindowFacts.dimAmount(rootWith(null)), 0f)
    }

    @Test
    fun imeRect_isTheBottomBandOfTheScreen() {
        assertEquals(MaskRect(0, 1500, 1080, 2000), WindowFacts.imeRect(1080, 2000, 500))
        assertNull(WindowFacts.imeRect(1080, 2000, 0))
        assertNull(WindowFacts.imeRect(1080, 2000, -3))
        assertEquals(MaskRect(0, 0, 1080, 2000), WindowFacts.imeRect(1080, 2000, 9_999)) // never above the screen
    }

    @Test
    fun imeHeight_isZeroForAViewWithoutInsets() {
        assertEquals(0, WindowFacts.imeHeightPx(FrameLayout(context)))
    }
}
