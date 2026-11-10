package id.art.tracker.replay

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.core.SdkLog
import id.art.tracker.privacy.MaskingPolicy
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

/**
 * Builds the production [ReplayController]; Compose support only when the Compose runtime is present. A kit that cannot
 * be built throws: the core then runs without session replay.
 */
internal object ReplayControllerFactory {
    fun create(core: ARTrackerCore, mainHandler: Handler): ReplayController {
        val policy = MaskingPolicy(core.config.privacy)
        val composeMask: ComposeMaskApi? = if (ClassAvailability.compose) {
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
        // A closed screen chunk goes to the upload queue; nothing after an opt-out.
        val sink = ReplaySink { batch -> if (!core.isDisabled()) core.replaySink.accept(batch) }
        val kit = ScreenshotKit(
            capturer = ScreenshotCapturer(BitmapPool(), MaskCollector(policy, composeMask), mainHandler, pixelCopy = pixelCopy),
            encoder = ScreenshotEncoder(),
            chunker = ScreenChunker(sink, core.clock, core.config.environment, userId = { core.identity.userId }),
        )
        return ReplayController.create(core, kit, mainHandler)
    }
}
