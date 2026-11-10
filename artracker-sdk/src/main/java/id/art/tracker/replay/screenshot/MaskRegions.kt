package id.art.tracker.replay.screenshot

/** A rectangle in screen pixels: [left, right) x [top, bottom). */
internal data class MaskRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(o: MaskRect): Boolean = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom
}
