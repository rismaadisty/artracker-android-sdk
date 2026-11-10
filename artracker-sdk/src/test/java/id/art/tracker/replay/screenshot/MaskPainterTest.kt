package id.art.tracker.replay.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskPainterTest {
    private fun bitmap(color: Int = Color.RED) = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun maskedRegion_holdsNoneOfTheOriginalPixels_andNothingOutsideChanges() {
        val b = bitmap()
        MaskPainter().paintMask(Canvas(b), MaskRect(20, 10, 120, 60))
        val allowed = setOf(MaskPainter.MASK_BASE, MaskPainter.MASK_STRIPE)
        for (y in 10 until 60) for (x in 20 until 120) {
            assertTrue("pixel ($x,$y)=${Integer.toHexString(b.getPixel(x, y))}", b.getPixel(x, y) in allowed)
        }
        assertEquals(Color.RED, b.getPixel(19, 30))
        assertEquals(Color.RED, b.getPixel(120, 30))
        assertEquals(Color.RED, b.getPixel(50, 9))
        assertEquals(Color.RED, b.getPixel(50, 60))
    }

    @Test
    fun theMaskIsAPattern_notAFlatFill() {
        val b = bitmap()
        MaskPainter().paintMask(Canvas(b), MaskRect(0, 0, 200, 100))
        val seen = HashSet<Int>()
        for (y in 0 until 100) for (x in 0 until 200) seen += b.getPixel(x, y)
        assertEquals(setOf(MaskPainter.MASK_BASE, MaskPainter.MASK_STRIPE), seen)
    }

    @Test
    fun aRectLargerThanTheBitmap_isClippedAndDoesNotThrow() {
        val b = bitmap()
        MaskPainter().paintMask(Canvas(b), MaskRect(-50, -50, 500, 500))
        assertNotEquals(Color.RED, b.getPixel(0, 0))
        assertNotEquals(Color.RED, b.getPixel(199, 99))
    }

    @Test
    fun keyboardPlaceholder_isFlatGreyWithALabel() {
        val b = bitmap()
        MaskPainter().paintKeyboard(Canvas(b), MaskRect(0, 40, 200, 100))
        assertEquals(MaskPainter.KEYBOARD_GREY, b.getPixel(2, 42))
        assertEquals(MaskPainter.KEYBOARD_GREY, b.getPixel(197, 97))
        assertEquals(Color.RED, b.getPixel(100, 39))
        // the "keyboard" label draws a few non-grey pixels in the middle
        var labelPixels = 0
        for (y in 40 until 100) for (x in 0 until 200) if (b.getPixel(x, y) != MaskPainter.KEYBOARD_GREY) labelPixels++
        assertTrue(labelPixels > 0)
    }
}
