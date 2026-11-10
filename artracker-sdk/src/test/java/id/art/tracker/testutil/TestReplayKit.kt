package id.art.tracker.testutil

import android.os.Handler
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.ReplayController
import id.art.tracker.replay.ReplaySink
import id.art.tracker.replay.screenshot.BitmapPool
import id.art.tracker.replay.screenshot.ByteBudgetGovernor
import id.art.tracker.replay.screenshot.MaskCollector
import id.art.tracker.replay.screenshot.PixelCopyApi
import id.art.tracker.replay.screenshot.ScreenChunker
import id.art.tracker.replay.screenshot.ScreenshotCapturer
import id.art.tracker.replay.screenshot.ScreenshotEncoder
import id.art.tracker.replay.screenshot.ScreenshotKit
import kotlinx.coroutines.CoroutineDispatcher

/**
 * A [ScreenshotKit] for tests: windows are drawn with `View.draw` (no `PixelCopy`), and closed chunks go to [sink]
 * (by default the core's own replay sink, as in production).
 */
internal fun testScreenshotKit(
    core: ARTrackerCore,
    handler: Handler,
    sink: ReplaySink = ReplaySink { core.replaySink.accept(it) },
    governorBudget: Long = ByteBudgetGovernor.DEFAULT_BUDGET_BYTES,
): ScreenshotKit = ScreenshotKit(
    capturer = ScreenshotCapturer(
        BitmapPool(), MaskCollector(MaskingPolicy(core.config.privacy)), handler, sdkInt = 25,
        pixelCopy = PixelCopyApi { _, _, done -> done(PixelCopyApi.ERROR) },
    ),
    encoder = ScreenshotEncoder(),
    chunker = ScreenChunker(sink, core.clock, core.config.environment, { core.identity.userId }),
    governorFactory = { fps, quality -> ByteBudgetGovernor(fps, quality, budgetBytes = governorBudget) },
)

/** A controller on [processing] (null: the production background context) that records screenshots into [kit]. */
internal fun testReplayController(
    core: ARTrackerCore,
    handler: Handler,
    processing: CoroutineDispatcher?,
    kit: ScreenshotKit = testScreenshotKit(core, handler),
    sampler: (String, Double) -> Boolean = { _, _ -> true },
): ReplayController = ReplayController.create(core, kit, handler, sampler, processing)
