package id.art.tracker.replay.capture

import android.app.Activity
import android.view.View

/**
 * Finds the root views to record (activity windows, dialogs, bottom sheets, popups).
 * Reads `WindowManagerGlobal.mViews` by reflection; falls back to the activity's decor view
 * when that is blocked by hidden API restrictions. Never throws.
 */
internal object RootViewLocator {

    /** Attached, visible roots in z-order (last = top), or the fallback decor view. */
    fun roots(fallback: Activity?): List<View> {
        val reflected = runCatching { reflectedRoots() }.getOrNull()
        if (!reflected.isNullOrEmpty()) return reflected
        return runCatching { listOfNotNull(fallback?.window?.decorView) }.getOrDefault(emptyList())
    }

    private fun reflectedRoots(): List<View> {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val instance = globalClass.getMethod("getInstance").invoke(null)
        val field = globalClass.getDeclaredField("mViews")
        field.isAccessible = true
        val views = field.get(instance) as? List<*> ?: return emptyList()
        // Copy first: the list is mutated on the main thread by the window manager.
        return ArrayList(views).filterIsInstance<View>()
            .filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }
    }
}
