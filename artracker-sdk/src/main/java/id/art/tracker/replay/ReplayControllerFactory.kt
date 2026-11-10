package id.art.tracker.replay

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import id.art.tracker.ReplayMode
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.core.SdkLog
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.capture.ComposeWalkerApi
import id.art.tracker.replay.capture.ComposeWalkerFactory
import id.art.tracker.replay.capture.ViewWalker
import id.art.tracker.replay.screenshot.BitmapPool
import id.art.tracker.replay.screenshot.ComposeMaskApi
import id.art.tracker.replay.screenshot.ComposeMaskCollectorFactory
import id.art.tracker.replay.screenshot.MaskCollector
import id.art.tracker.replay.screenshot.PixelCopyApi
import id.art.tracker.replay.screenshot.RealPixelCopy
import id.art.tracker.replay.screenshot.ScreenChunker
import id.art.tracker.replay.screenshot.ScreenshotCapturer
import id.art.tracker.replay.screenshot.ScreenshotEncoder
import id.art.tracker.replay.screenshot.ScreenshotKit

/** Builds the production [ReplayController]; Compose support only when the Compose runtime is present. */
internal object ReplayControllerFactory {
    fun create(core: ARTrackerCore, mainHandler: Handler): ReplayController {
        val policy = MaskingPolicy(core.config.privacy)
        val composeAvailable = ClassAvailability.compose
        val compose: ComposeWalkerApi? = if (composeAvailable) {
            runCatching { ComposeWalkerFactory.create(policy) }
                .onFailure { SdkLog.w("Compose replay support unavailable", it) }
                .getOrNull()
        } else {
            null
        }
        val screenshot = if (core.config.replay.mode == ReplayMode.SCREENSHOT) {
            runCatching { screenshotKit(core, policy, composeAvailable, mainHandler) }
                .onFailure { SdkLog.w("screenshot replay unavailable; recording wireframes", it) }
                .getOrNull()
        } else {
            null
        }
        return ReplayController.create(
            core, ViewWalker(policy, compose), mainHandler,
            screenshot = screenshot,
            remote = core.replayRemote,
            clearScreenshotQueue = core::clearScreenshotChunks,
        )
    }

    private fun screenshotKit(core: ARTrackerCore, policy: MaskingPolicy, composeAvailable: Boolean, mainHandler: Handler): ScreenshotKit {
        val composeMask: ComposeMaskApi? = if (composeAvailable) {
            runCatching { ComposeMaskCollectorFactory.create(policy) }
                .onFailure { SdkLog.w("Compose screenshot masking unavailable", it) }
                .getOrNull()
        } else {
            null
        }
        val pixelCopy: PixelCopyApi = if (Build.VERSION.SDK_INT >= 26) {
            RealPixelCopy(Handler(HandlerThread("artracker-pixelcopy").apply { start() }.looper))
        } else {
            PixelCopyApi { _, _, done -> done(PixelCopyApi.ERROR) }
        }
        // A closed screen chunk goes to the same upload queue as the wireframe batches; nothing after an opt-out.
        val sink = ReplaySink { batch -> if (!core.isDisabled()) core.replaySink.accept(batch) }
        return ScreenshotKit(
            capturer = ScreenshotCapturer(BitmapPool(), MaskCollector(policy, composeMask), mainHandler, pixelCopy = pixelCopy),
            encoder = ScreenshotEncoder(),
            chunker = ScreenChunker(sink, core.clock, core.config.environment, userId = { core.identity.userId }),
        )
    }
}
