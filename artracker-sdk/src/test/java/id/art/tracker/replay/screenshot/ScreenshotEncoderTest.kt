package id.art.tracker.replay.screenshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import id.art.tracker.ReplayQuality
import id.art.tracker.preset
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The processing-thread half: mask, fingerprint, scale, JPEG. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotEncoderTest {
    private val standard = ReplayQuality.STANDARD.preset()

    private fun raw(
        w: Int = 400,
        h: Int = 800,
        color: Int = Color.RED,
        masks: List<MaskRect> = emptyList(),
        secure: Int = 0,
        keyboard: MaskRect? = null,
        ts: Long = 10L,
    ): RawFrame = RawFrame(
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) },
        ts, masks, secure, keyboard, onRelease = {},
    )

    private fun decode(f: ScreenFrame): Bitmap = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)

    private fun encoder() = ScreenshotEncoder()

    @Test
    fun maskedRegions_areMaskPixelsInTheDecodedJpeg_andTheRestIsUntouched() {
        val f = encoder().encode(raw(masks = listOf(MaskRect(100, 200, 300, 400))), standard, "/home", 1f, force = false)!!
        val img = decode(f)
        val inside = img.getPixel(200, 300)
        val outside = img.getPixel(50, 50)
        // grey mask pattern (not red) inside; red outside (JPEG tolerance)
        assertTrue("inside=${Integer.toHexString(inside)}", Color.red(inside) in 90..200 && Math.abs(Color.red(inside) - Color.green(inside)) < 30)
        assertTrue("outside=${Integer.toHexString(outside)}", Color.red(outside) > 220 && Color.green(outside) < 40)
    }

    @Test
    fun theKeyboardBand_isAFlatPlaceholder() {
        val f = encoder().encode(raw(keyboard = MaskRect(0, 500, 400, 800)), standard, "/home", 1f, force = false)!!
        val p = decode(f).getPixel(10, 510)
        assertTrue(Color.red(p) in 205..240 && Math.abs(Color.red(p) - Color.blue(p)) < 12)
    }

    @Test
    fun maskedCount_isTheMaskRegions_secureWindowsIncluded_keyboardNotCounted() {
        // the capturer lists a FLAG_SECURE window's area among the masks (secure = 1 of the 3)
        val f = encoder().encode(
            raw(masks = listOf(MaskRect(0, 0, 10, 10), MaskRect(20, 20, 30, 30), MaskRect(0, 0, 400, 800)), secure = 1, keyboard = MaskRect(0, 700, 400, 800)),
            standard, "/s", 1f, force = false,
        )!!
        assertEquals(3, f.masked)
    }

    @Test
    fun theImageIsScaledToTheShortSideOfThePreset_neverUpscaled() {
        val big = encoder().encode(raw(1080, 2400), standard, "/s", 3f, force = false)!!
        assertEquals(720, big.width); assertEquals(1600, big.height)
        assertEquals(360, big.viewportWidthDp); assertEquals(800, big.viewportHeightDp) // dp of the SCREEN, not the image

        val small = encoder().encode(raw(400, 800), standard, "/s", 1f, force = false)!!
        assertEquals(400, small.width); assertEquals(800, small.height)

        val high = encoder().encode(raw(1080, 2400), ReplayQuality.HIGH.preset(), "/s", 3f, force = false)!!
        assertEquals(1080, high.width)
        val low = encoder().encode(raw(1080, 2400), ReplayQuality.LOW.preset(), "/s", 3f, force = false)!!
        assertEquals(480, low.width); assertEquals(1067, low.height)
    }

    @Test
    fun landscape_usesTheShortSideToo() {
        val f = encoder().encode(raw(2400, 1080), standard, "/s", 3f, force = false)!!
        assertEquals(1600, f.width); assertEquals(720, f.height)
    }

    @Test
    fun lowerQuality_givesSmallerJpegs() {
        fun noisy(): RawFrame {
            val r = Random(7)
            val b = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
            val px = IntArray(500 * 500) { Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
            b.setPixels(px, 0, 500, 0, 0, 500, 500)
            return RawFrame(b, 1L, emptyList(), 0, null) {}
        }
        val low = encoder().encode(noisy(), ReplayQuality.LOW.preset(), "/s", 1f, force = false)!!
        val high = encoder().encode(noisy(), ReplayQuality.HIGH.preset(), "/s", 1f, force = false)!!
        assertTrue("${low.jpeg.size} < ${high.jpeg.size}", low.jpeg.size < high.jpeg.size)
    }

    @Test
    fun aFrameIdenticalToTheLastOne_isSuppressed_unlessForced() {
        val e = encoder()
        assertNotNull(e.encode(raw(), standard, "/s", 1f, force = false))
        assertNull(e.encode(raw(), standard, "/s", 1f, force = false))
        assertNotNull(e.encode(raw(), standard, "/s", 1f, force = true))
        assertNotNull(e.encode(raw(color = Color.BLUE), standard, "/s", 1f, force = false))
    }

    @Test
    fun aMaskedChange_doesNotMakeTheFrameDifferent() {
        // What is under a mask never leaves the device and must not defeat duplicate suppression either.
        val e = encoder()
        val m = listOf(MaskRect(0, 0, 400, 800))
        assertNotNull(e.encode(raw(color = Color.RED, masks = m), standard, "/s", 1f, force = false))
        assertNull(e.encode(raw(color = Color.BLUE, masks = m), standard, "/s", 1f, force = false))
    }

    @Test
    fun frameFieldsAreCarried() {
        val f = encoder().encode(raw(ts = 99L), standard, "/checkout", 1f, force = false)!!
        assertEquals(99L, f.ts)
        assertEquals("/checkout", f.screen)
        assertTrue(f.jpeg.size > 100)
    }
}
