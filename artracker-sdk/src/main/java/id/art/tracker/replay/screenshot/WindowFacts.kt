package id.art.tracker.replay.screenshot

import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Facts about a window that decide how its screenshot is taken. Main thread, read-only, never throws. */
internal object WindowFacts {
    private fun params(root: View): WindowManager.LayoutParams? = root.layoutParams as? WindowManager.LayoutParams

    /** `FLAG_SECURE`: the app asked that nothing of this window is captured. */
    fun isSecure(root: View): Boolean = params(root)?.let { it.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 } ?: false

    /**
     * A sub-window (`FIRST_SUB_WINDOW..LAST_SUB_WINDOW`): `PopupWindow`, `ListPopupWindow`, the dropdown of an
     * `AutoCompleteTextView` or `Spinner` (1000), Compose `Popup` / `DropdownMenu` (1002). They hang off a view of
     * another window, which they do not expose. False for a root without window parameters; never throws.
     */
    fun isPopup(root: View): Boolean = try {
        params(root)?.type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW
    } catch (_: Throwable) {
        false
    }

    /** How dark the window dims what is behind it (0..1); 0 when it does not dim. */
    fun dimAmount(root: View): Float = params(root)
        ?.takeIf { it.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 }
        ?.dimAmount?.coerceIn(0f, 1f) ?: 0f

    /** The bottom band covered by the keyboard, or null when there is none. */
    fun imeRect(screenWidth: Int, screenHeight: Int, imeHeightPx: Int): MaskRect? {
        if (imeHeightPx <= 0) return null
        return MaskRect(0, (screenHeight - imeHeightPx).coerceAtLeast(0), screenWidth, screenHeight)
    }

    /** Height of the visible keyboard in pixels, 0 when it is hidden or unknown. */
    fun imeHeightPx(root: View): Int = try {
        val insets = ViewCompat.getRootWindowInsets(root)
        if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
    } catch (_: Throwable) {
        0
    }
}
