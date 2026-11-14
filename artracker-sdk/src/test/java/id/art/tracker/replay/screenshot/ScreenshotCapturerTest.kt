package id.art.tracker.replay.screenshot

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.privacy.artrackerMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The main-thread half of a screenshot: composite the windows, collect what must be masked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotCapturerTest {
    private val handler = Handler(Looper.getMainLooper())
    private val pool = BitmapPool()
    private lateinit var activity: Activity
    private lateinit var host: FrameLayout

    // WindowManager.LayoutParams cannot sit on a child of a FrameLayout, so window facts are injected by view.
    private val secureViews = HashSet<View>()
    private val popupViews = HashSet<View>()
    private val dims = HashMap<View, Float>()

    private fun setUpScreen() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        activity = controller.get()
        host = FrameLayout(activity)
        activity.setContentView(host)
        controller.start().postCreate(null).resume().visible()
    }

    /** A solid [color] root of [w]x[h] at ([x],[y]) inside the host, laid out by hand. */
    private fun root(color: Int, x: Int, y: Int, w: Int, h: Int): View {
        val v = FrameLayout(activity).apply { setBackgroundColor(color) }
        host.addView(v, FrameLayout.LayoutParams(w, h))
        host.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 400, 800)
        v.layout(x, y, x + w, y + h)
        return v
    }

    private fun origin(v: View) = IntArray(2).also { v.getLocationOnScreen(it) }

    private fun capturer(
        sdkInt: Int = 25,
        pixelCopy: PixelCopyApi = PixelCopyApi { _, _, done -> done(PixelCopyApi.ERROR) },
        imeHeight: (View) -> Int = { 0 },
        config: PrivacyConfig = PrivacyConfig(),
        decorOf: (Window) -> View = { it.decorView },
    ) = ScreenshotCapturer(
        pool, MaskCollector(MaskingPolicy(config)), handler, sdkInt, pixelCopy, imeHeight, decorOf,
        isSecure = { it in secureViews }, dimAmount = { dims[it] ?: 0f },
        isPopup = { it in popupViews }, anchorTolerancePx = { 8 },
    )

    private fun run(c: ScreenshotCapturer, roots: List<View>, base: Window? = null): RawFrame? {
        var result: RawFrame? = null
        var called = false
        c.capture(roots, base, 1_234L) { result = it; called = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("callback must be called", called)
        return result
    }

    @Test
    fun oldDevices_drawEveryRootWithViewDraw_atItsScreenPosition() {
        setUpScreen()
        val a = root(Color.RED, 0, 0, 400, 800)
        val b = root(Color.BLUE, 100, 200, 200, 100)
        val oa = origin(a)
        val ob = origin(b)
        val raw = run(capturer(), listOf(a, b))!!
        assertEquals(1_234L, raw.ts)
        assertEquals(Color.RED, raw.bitmap.getPixel(oa[0] + 5, oa[1] + 5))
        assertEquals(Color.BLUE, raw.bitmap.getPixel(ob[0] + 50, ob[1] + 50))
        assertTrue(raw.masks.isEmpty())
        assertEquals(0, raw.secureCount)
    }

    @Test
    fun aDialogDimsWhatIsBehindIt() {
        setUpScreen()
        val a = root(Color.WHITE, 0, 0, 400, 800)
        val dialog = root(Color.BLUE, 100, 200, 200, 100)
        dims[dialog] = 0.5f
        val oa = origin(a)
        val ob = origin(dialog)
        val raw = run(capturer(), listOf(a, dialog))!!
        val behind = raw.bitmap.getPixel(oa[0] + 5, oa[1] + 5)
        assertEquals(Color.red(behind).toDouble(), 127.5, 2.0) // white dimmed by 50 %
        assertEquals(Color.BLUE, raw.bitmap.getPixel(ob[0] + 50, ob[1] + 50)) // the dialog itself is not dimmed
    }

    @Test
    fun aFlagSecureWindow_isNeverDrawn_andItsAreaIsMasked() {
        setUpScreen()
        var drawn = false
        val secure = object : FrameLayout(activity) {
            override fun draw(canvas: Canvas) {
                drawn = true
                super.draw(canvas)
            }
        }.apply {
            setBackgroundColor(Color.GREEN)
        }
        secureViews += secure
        host.addView(secure)
        host.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 400, 800)
        secure.layout(0, 0, 400, 800)
        var copyRequested = false
        val c = capturer(
            sdkInt = 34, pixelCopy = { _, _, done -> copyRequested = true; done(PixelCopyApi.SUCCESS) },
            decorOf = { secure },
        )
        val raw = run(c, listOf(secure), base = activity.window)!!
        assertFalse("a secure window must not be drawn", drawn)
        assertFalse("a secure window must not be copied", copyRequested)
        assertEquals(1, raw.secureCount)
        val o = origin(secure)
        assertTrue(raw.masks.contains(MaskRect(o[0], o[1], o[0] + 400, o[1] + 800)))
        assertFalse(Color.GREEN == raw.bitmap.getPixel(o[0] + 5, o[1] + 5))
    }

    @Test
    fun maskRects_ofSensitiveViews_travelWithTheFrame() {
        setUpScreen()
        val a = root(Color.WHITE, 0, 0, 400, 800)
        val secret = View(activity).apply { artrackerMask() }
        (a as FrameLayout).addView(secret, FrameLayout.LayoutParams(100, 30))
        a.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        a.layout(0, 0, 400, 800)
        val o = origin(secret)
        val raw = run(capturer(), listOf(a))!!
        assertEquals(listOf(MaskRect(o[0], o[1], o[0] + 100, o[1] + 30)), raw.masks)
    }

    @Test
    fun aFailedMaskCollection_dropsTheFrame_andFreesTheBuffer() {
        setUpScreen()
        val a = root(Color.WHITE, 0, 0, 400, 800)
        val bad = object : View(activity) {
            override fun getLocationOnScreen(outLocation: IntArray) = throw IllegalStateException("boom")
        }.apply { artrackerMask() }
        (a as FrameLayout).addView(bad, FrameLayout.LayoutParams(10, 10))
        a.layout(0, 0, 400, 800)
        bad.layout(0, 0, 10, 10)
        assertNull(run(capturer(), listOf(a)))
        // both buffers are still available
        assertNotNull(pool.acquire(10, 10))
        assertNotNull(pool.acquire(10, 10))
    }

    @Test
    fun theKeyboard_becomesAPlaceholderBand_atTheBottom() {
        setUpScreen()
        val a = root(Color.WHITE, 0, 0, 400, 800)
        val o = origin(a)
        val raw = run(capturer(imeHeight = { 300 }), listOf(a))!!
        assertEquals(MaskRect(0, o[1] + 800 - 300, o[0] + 400, o[1] + 800), raw.keyboard)
    }

    @Test
    fun newerDevices_copyTheBaseWindowWithPixelCopy_andDrawTheOverlaysOnTop() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800)
        val overlay = root(Color.BLUE, 100, 200, 200, 100)
        val ob = origin(overlay)
        val window = activity.window
        var requested: Window? = null
        val c = capturer(sdkInt = 34, pixelCopy = { w, dest, done ->
            requested = w
            dest.eraseColor(Color.MAGENTA) // stands in for the real window pixels
            done(PixelCopyApi.SUCCESS)
        }, decorOf = { base }) // pretend the activity window's decor is the base root
        val raw = run(c, listOf(base, overlay), base = window)!!
        assertEquals(window, requested)
        val o = origin(base)
        assertEquals(Color.MAGENTA, raw.bitmap.getPixel(o[0] + 5, o[1] + 5))
        assertEquals(Color.BLUE, raw.bitmap.getPixel(ob[0] + 50, ob[1] + 50))
    }

    @Test
    fun aFailedPixelCopy_fallsBackToViewDraw() {
        setUpScreen()
        val base = root(Color.RED, 0, 0, 400, 800)
        val o = origin(base)
        val c = capturer(sdkInt = 34, pixelCopy = { _, _, done -> done(PixelCopyApi.ERROR) }, decorOf = { base })
        val raw = run(c, listOf(base), base = activity.window)!!
        assertEquals(Color.RED, raw.bitmap.getPixel(o[0] + 5, o[1] + 5))
    }

    @Test
    fun release_returnsTheBufferToThePool() {
        setUpScreen()
        val a = root(Color.RED, 0, 0, 400, 800)
        val c = capturer()
        val first = run(c, listOf(a))!!
        val second = run(c, listOf(a))!!
        // both buffers are out: a third capture is skipped, not queued
        var thirdCalled = false
        var third: RawFrame? = null
        c.capture(listOf(a), null, 3L) { third = it; thirdCalled = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(thirdCalled)
        assertNull(third)
        first.release()
        assertNotNull(run(c, listOf(a)))
        second.release()
    }

    private fun addSecret(parent: FrameLayout): View {
        val secret = View(activity).apply { artrackerMask() }
        parent.addView(secret, FrameLayout.LayoutParams(100, 30))
        secret.layout(0, 0, 100, 30)
        return secret
    }

    @Test
    fun theBaseMasks_areCollectedAgainAfterThePixelCopy_soAMovedViewStaysCovered() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        val secret = addSecret(base)
        val before = IntArray(2).also { secret.getLocationOnScreen(it) }
        val after = IntArray(2)
        val c = capturer(sdkInt = 34, pixelCopy = { _, dest, done ->
            dest.eraseColor(Color.WHITE)
            secret.translationY = 300f // the view moves while the copy is in flight
            secret.getLocationOnScreen(after)
            done(PixelCopyApi.SUCCESS)
        }, decorOf = { base })
        val raw = run(c, listOf(base), base = activity.window)!!
        assertTrue(after[1] > before[1])
        assertTrue("old position still masked", raw.masks.any { it.top == before[1] })
        assertTrue("new position masked: ${raw.masks}", raw.masks.any { it.top == after[1] })
    }

    @Test
    fun anOverlaysMasks_areCollectedWhenItIsDrawn_notWhenTheCaptureStarted() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800)
        val overlay = root(Color.BLUE, 100, 200, 200, 300) as FrameLayout
        val secret = addSecret(overlay)
        val after = IntArray(2)
        val c = capturer(sdkInt = 34, pixelCopy = { _, dest, done ->
            dest.eraseColor(Color.WHITE)
            secret.translationY = 200f // a bottom sheet sliding up / an IME resize while the copy runs
            done(PixelCopyApi.SUCCESS)
        }, decorOf = { base })
        val startedAt = IntArray(2).also { secret.getLocationOnScreen(it) }
        val raw = run(c, listOf(base, overlay), base = activity.window)!!
        secret.getLocationOnScreen(after) // the translation persists: this is where the field really is when drawn
        assertTrue(after[1] > startedAt[1] - 1)
        assertTrue("moved overlay field masked: ${raw.masks}", raw.masks.any { it.top == after[1] && it.bottom == after[1] + 30 })
    }

    @Test
    fun aThrowWhileCompositingTheCopy_dropsTheFrame_insteadOfCrashingTheHost() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800)
        val wide = root(Color.BLUE, 100, 100, 400, 100) // makes the bitmap wider than the base: the copy goes through a temp bitmap
        val c = capturer(sdkInt = 34, pixelCopy = { _, dest, done ->
            dest.recycle() // drawing a recycled bitmap throws
            done(PixelCopyApi.SUCCESS)
        }, decorOf = { base })
        var called = false
        var result: RawFrame? = null
        c.capture(listOf(base, wide), activity.window, 5L) { result = it; called = true }
        shadowOf(Looper.getMainLooper()).idle() // must not throw
        assertTrue(called)
        assertNull(result)
        assertNotNull(pool.acquire(10, 10))
        assertNotNull(pool.acquire(10, 10))
    }

    // region popups: a popup hangs off a view of a window below it, which it does not say

    /** A child of [parent] at ([x],[y]) inside it, [w]x[h], laid out by hand (the parent keeps its own place). */
    private fun <T : View> place(parent: FrameLayout, child: T, x: Int, y: Int, w: Int, h: Int): T {
        parent.addView(child, FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y })
        parent.measure(View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.EXACTLY))
        parent.layout(parent.left, parent.top, parent.right, parent.bottom)
        return child
    }

    private fun rectOf(v: View) = origin(v).let { MaskRect(it[0], it[1], it[0] + v.width, it[1] + v.height) }

    // The rect of a fake window is read BEFORE the capture: run() idles the looper, which lays the host out again.
    private fun assertMasked(raw: RawFrame, popup: MaskRect) =
        assertTrue("the popup $popup should be masked whole, masks = ${raw.masks}", raw.masks.contains(popup))

    private fun popupRoot(x: Int, y: Int, w: Int, h: Int): View = root(Color.WHITE, x, y, w, h).also { popupViews += it }

    @Test
    fun autoCompleteDropdownBelowAMaskedField_isMaskedWhole() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        place(base, EditText(activity), 0, 100, 200, 40) // masked by maskAllInputs, the default
        val popup = popupRoot(0, 140, 200, 160)

        val target = rectOf(popup)
        val raw = run(capturer(), listOf(base, popup))!!

        assertMasked(raw, target)
    }

    @Test
    fun popupAnchoredInsideAMaskedContainer_isMasked() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        val container = place(base, FrameLayout(activity).apply { tag = "artracker-mask" }, 0, 100, 400, 400)
        val button = place(container, Button(activity), 10, 100, 100, 40)
        val popup = popupRoot(10, 240, 200, 160)

        val target = rectOf(popup)
        val buttonRect = rectOf(button)
        val raw = run(capturer(), listOf(base, popup))!!

        assertMasked(raw, target)
        assertTrue("the container is one rect, the anchor adds none", raw.masks.none { it == buttonRect })
    }

    @Test
    fun anOrdinaryPopupAwayFromMaskedAreas_isNotMasked() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        place(base, EditText(activity), 0, 300, 200, 40)
        val popup = popupRoot(200, 0, 200, 200) // a toolbar overflow menu, nowhere near the field

        val target = rectOf(popup)
        val raw = run(capturer(), listOf(base, popup))!!

        assertFalse(raw.masks.contains(target))
        assertTrue(raw.masks.none { target.contains(it) })
    }

    @Test
    fun aFullHeightSheetOverAMaskedComposer_isNotMaskedWhole() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        place(base, EditText(activity), 0, 760, 400, 40) // the chat composer at the bottom
        val sheet = popupRoot(0, 0, 400, 800)

        val target = rectOf(sheet)
        val raw = run(capturer(), listOf(base, sheet))!!

        assertFalse(raw.masks.contains(target))
    }

    @Test
    fun aSheetStartingBelowTheStatusBar_overAMaskedComposer_isNotMaskedWhole() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        place(base, EditText(activity), 0, 760, 400, 40)
        val sheet = popupRoot(0, 48, 400, 752) // like Compose's Popup: measured against the visible frame

        val target = rectOf(sheet)
        val raw = run(capturer(), listOf(base, sheet))!!

        assertFalse(raw.masks.contains(target))
    }

    @Test
    fun aMenuShiftedOverItsMaskedAnchor_isMasked() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        place(base, EditText(activity), 0, 300, 200, 40)
        val menu = popupRoot(0, 200, 200, 300) // top above the field, bottom below it

        val target = rectOf(menu)
        val raw = run(capturer(), listOf(base, menu))!!

        assertMasked(raw, target)
    }

    @Test
    fun aDialogNextToAMaskedField_isNotTreatedAsAPopup() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        place(base, EditText(activity), 0, 100, 200, 40)
        val dialog = root(Color.WHITE, 0, 140, 200, 160) // same place as the dropdown, but its window is not a sub-window

        val target = rectOf(dialog)
        val raw = run(capturer(), listOf(base, dialog))!!

        assertFalse(raw.masks.contains(target))
    }

    @Test
    fun aPopupOverAFlagSecureWindow_isMasked() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800)
        secureViews += base
        val popup = popupRoot(50, 50, 100, 100)

        val target = rectOf(popup)
        val raw = run(capturer(), listOf(base, popup))!!

        assertMasked(raw, target)
    }

    @Test
    fun aPopupOverADialog_inheritsFromTheDialogsField() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800)
        val dialog = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        place(dialog, EditText(activity), 0, 400, 200, 40)
        val popup = popupRoot(0, 440, 200, 160)

        val target = rectOf(popup)
        val raw = run(capturer(), listOf(base, dialog, popup))!!

        assertMasked(raw, target)
    }

    @Test
    fun newerDevices_thePopupOfAMaskedAnchorIsMaskedToo() {
        setUpScreen()
        val base = root(Color.WHITE, 0, 0, 400, 800) as FrameLayout
        place(base, EditText(activity), 0, 100, 200, 40)
        val popup = popupRoot(0, 140, 200, 160)
        val c = capturer(sdkInt = 34, pixelCopy = { _, dest, done ->
            dest.eraseColor(Color.MAGENTA)
            done(PixelCopyApi.SUCCESS)
        }, decorOf = { base })

        val target = rectOf(popup)
        val raw = run(c, listOf(base, popup), base = activity.window)!!

        assertMasked(raw, target)
    }

    // endregion
}
