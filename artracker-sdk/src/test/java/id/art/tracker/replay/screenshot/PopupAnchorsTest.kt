package id.art.tracker.replay.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The geometry that tells whether a popup hangs off a masked area of the window below it. */
class PopupAnchorsTest {
    private val base = MaskRect(0, 0, 400, 800)
    private val tol = 8
    private val field = MaskRect(0, 100, 200, 140)

    private fun inherits(popup: MaskRect, vararg masks: MaskRect) = PopupAnchors.inheritsMask(popup, masks.toList(), base, tol)

    @Test
    fun popupDroppingBelowAMaskedRect_inherits() {
        assertTrue(inherits(MaskRect(0, 140, 200, 300), field))
    }

    @Test
    fun popupOverlappingTheAnchorBottom_inherits() {
        assertTrue(inherits(MaskRect(0, 134, 200, 300), field)) // an inset background starts inside the field
    }

    @Test
    fun dropUpPopupAboveAMaskedRect_inherits() {
        assertTrue(inherits(MaskRect(0, 20, 200, 100), field))
    }

    @Test
    fun popupCenteredInsideAMaskedContainer_inherits() {
        assertTrue(inherits(MaskRect(100, 300, 300, 400), MaskRect(0, 100, 400, 600)))
    }

    @Test
    fun gapLargerThanTolerance_doesNotInherit() {
        assertFalse(inherits(MaskRect(0, 149, 200, 300), field))
    }

    @Test
    fun noHorizontalOverlap_doesNotInherit() {
        // The right edge of the mask is exclusive: a popup that starts exactly there does not overlap it.
        assertFalse(inherits(MaskRect(100, 140, 300, 300), MaskRect(0, 100, 100, 140)))
    }

    @Test
    fun fullHeightSubWindow_neverInherits() {
        assertFalse(inherits(MaskRect(0, 0, 400, 800), MaskRect(0, 760, 400, 800)))
    }

    @Test
    fun noLowerMasks_doesNotInherit() {
        assertFalse(PopupAnchors.inheritsMask(MaskRect(0, 140, 200, 300), emptyList(), base, tol))
    }
}
