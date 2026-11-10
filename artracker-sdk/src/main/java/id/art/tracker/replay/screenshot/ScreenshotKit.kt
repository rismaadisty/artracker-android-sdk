package id.art.tracker.replay.screenshot

import id.art.tracker.ReplayQuality

/** The screenshot pieces a [id.art.tracker.replay.ReplayController] drives; built once per controller. */
internal class ScreenshotKit(
    val capturer: ScreenshotCapturer,
    val encoder: ScreenshotEncoder,
    val chunker: ScreenChunker,
    /** One governor per recording run: `(base fps, configured quality)`. */
    val governorFactory: (Double, ReplayQuality) -> ByteBudgetGovernor = { fps, quality -> ByteBudgetGovernor(fps, quality) },
)
