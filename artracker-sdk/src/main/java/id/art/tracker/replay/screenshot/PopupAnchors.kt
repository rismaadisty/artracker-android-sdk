package id.art.tracker.replay.screenshot

/**
 * Decides whether a popup inherits the masking of the area it hangs off.
 *
 * The anchor view cannot be read: `PopupWindow` keeps it behind a synthetic field the platform hides from apps, the
 * window token of a popup names the owning *window* and not the view, and Compose's `PopupLayout` keeps its anchor
 * bounds in a private field a host's R8 renames. So the relation is inferred from where the popup is: it overlaps a
 * masked area of a window below it horizontally, and its top or bottom edge lies on that area (within a tolerance,
 * for insets and shadows). A popup that covers the whole height of the base window is a sheet, not a dropdown, and
 * never inherits. A popup far from every masked area is left alone on purpose: masking every popup of a form screen
 * (where `maskAllInputs` makes most of the screen a mask) would hide ordinary menus for nothing.
 *
 * Pure geometry, no views.
 */
internal object PopupAnchors {
    const val ANCHOR_TOLERANCE_DP = 8

    fun inheritsMask(popup: MaskRect, lowerMasks: List<MaskRect>, base: MaskRect, tolerancePx: Int): Boolean {
        if (popup.top <= base.top + tolerancePx && popup.bottom >= base.bottom - tolerancePx) return false
        return lowerMasks.any { m ->
            val overlapsHorizontally = popup.left < m.right && m.left < popup.right
            val vertical = (m.top - tolerancePx)..(m.bottom + tolerancePx)
            overlapsHorizontally && (popup.top in vertical || popup.bottom in vertical)
        }
    }
}
