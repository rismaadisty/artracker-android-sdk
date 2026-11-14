package id.art.tracker.replay.screenshot

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import id.art.tracker.PrivacyConfig
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.privacy.artrackerMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A popup under a node masked with `Modifier.artrackerMask()`. Needs a real Compose host (a `ComponentActivity`), which
 * the plain-Activity [ScreenshotCapturerTest] does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopupMaskComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun composeDropdownBelowAMaskedNode_isMasked() {
        rule.setContent { Box(Modifier.size(100.dp, 40.dp).artrackerMask()) }
        rule.waitForIdle()
        val decor = rule.activity.window.decorView
        val policy = MaskingPolicy(PrivacyConfig())
        val collector = MaskCollector(policy, ComposeMaskCollector(policy))

        val node = ArrayList<MaskRect>()
        assertTrue(collector.collect(decor, node))
        assertEquals("the masked Box is the only region", 1, node.size)
        val anchor = node.single()

        // A sub-window root just below the node (Compose's Popup / DropdownMenu is one).
        val popup = FrameLayout(rule.activity)
        rule.activity.addContentView(popup, FrameLayout.LayoutParams(200, 160))
        rule.waitForIdle()
        popup.layout(anchor.left, anchor.bottom, anchor.left + 200, anchor.bottom + 160)
        val loc = IntArray(2).also { popup.getLocationOnScreen(it) }
        val popupRect = MaskRect(loc[0], loc[1], loc[0] + 200, loc[1] + 160)

        val capturer = ScreenshotCapturer(
            BitmapPool(), collector, Handler(Looper.getMainLooper()), sdkInt = 25,
            pixelCopy = PixelCopyApi { _, _, done -> done(PixelCopyApi.ERROR) },
            imeHeight = { 0 }, isSecure = { false }, dimAmount = { 0f },
            isPopup = { it === popup }, anchorTolerancePx = { 8 },
        )
        var frame: RawFrame? = null
        capturer.capture(listOf<View>(decor, popup), null, 1L) { frame = it }

        assertTrue(frame!!.masks.contains(popupRect))
    }
}
