package id.art.tracker.replay.screenshot

/**
 * Decides whether a popup inherits the masking of the area it hangs off.
 *
 * The anchor view cannot be read: `PopupWindow` keeps it behind a synthetic field the platform hides from apps, the
 * window token of a popup names the owning *window* and not the view, and Compose's `PopupLayout` keeps its anchor
 * bounds in a private field a host's R8 renames. So the relation is inferred from where the popup is: it overlaps a
 * masked area of a window below it horizontally, and its top or bottom edge lies on that area (within a tolerance,
 * for insets and shadows), or it spans the area from above to below (a menu that fits neither below nor above its
 * anchor is shifted over it). A popup that covers nearly the whole height of the base window ([SHEET_HEIGHT_FRACTION])
 * is a sheet, not a dropdown, and never inherits: a full-size Compose `Popup` is measured against the visible frame,
 * so it starts under the status bar and an exact "touches the top" test would miss it. A popup far from every masked area is left alone on purpose: masking every popup of a form screen
 * (where `maskAllInputs` makes most of the screen a mask) would hide ordinary menus for nothing.
 *
 * Pure geometry, no views.
 */
internal object PopupAnchors {
    const val ANCHOR_TOLERANCE_DP = 8

    /** A popup at least this share of the base window's height counts as a sheet. */
    const val SHEET_HEIGHT_FRACTION = 0.85

    fun inheritsMask(popup: MaskRect, lowerMasks: List<MaskRect>, base: MaskRect, tolerancePx: Int): Boolean {
        if ((popup.bottom - popup.top) >= (base.bottom - base.top) * SHEET_HEIGHT_FRACTION) return false
        return lowerMasks.any { m ->
            val overlapsHorizontally = popup.left < m.right && m.left < popup.right
            val vertical = (m.top - tolerancePx)..(m.bottom + tolerancePx)
            val spansTheArea = popup.top <= m.top && popup.bottom >= m.bottom
            overlapsHorizontally && (popup.top in vertical || popup.bottom in vertical || spansTheArea)
        }
    }
}
