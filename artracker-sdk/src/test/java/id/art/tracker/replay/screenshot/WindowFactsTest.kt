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

    private fun rootOfType(type: Int): View = FrameLayout(context).also { it.layoutParams = WindowManager.LayoutParams(type) }

    @Test
    fun subWindowTypes_arePopups() {
        for (type in listOf(
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, // PopupWindow, ListPopupWindow, ACTV / Spinner dropdowns
            WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL, // Compose Popup / DropdownMenu
            WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
        )) {
            assertTrue("type $type", WindowFacts.isPopup(rootOfType(type)))
        }
    }

    @Test
    fun applicationWindows_areNotPopups() {
        assertFalse(WindowFacts.isPopup(rootOfType(WindowManager.LayoutParams.TYPE_BASE_APPLICATION)))
        assertFalse(WindowFacts.isPopup(rootOfType(WindowManager.LayoutParams.TYPE_APPLICATION)))
    }

    @Test
    fun aRootWithoutWindowParams_isNotAPopup() {
        assertFalse(WindowFacts.isPopup(FrameLayout(context)))
        assertFalse(WindowFacts.isPopup(FrameLayout(context).also { it.layoutParams = android.view.ViewGroup.LayoutParams(10, 10) }))
    }
}
