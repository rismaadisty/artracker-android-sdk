package id.art.tracker.replay

import android.os.Handler
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.ClassAvailability
import id.art.tracker.core.SdkLog
import id.art.tracker.privacy.MaskingPolicy
import id.art.tracker.replay.capture.ComposeWalkerApi
import id.art.tracker.replay.capture.ComposeWalkerFactory
import id.art.tracker.replay.capture.ViewWalker

/** Builds the production [ReplayController]; Compose support only when the Compose runtime is present. */
internal object ReplayControllerFactory {
    fun create(core: ARTrackerCore, mainHandler: Handler): ReplayController {
        val policy = MaskingPolicy(core.config.privacy)
        val compose: ComposeWalkerApi? = if (ClassAvailability.compose) {
            runCatching { ComposeWalkerFactory.create(policy) }
                .onFailure { SdkLog.w("Compose replay support unavailable", it) }
                .getOrNull()
        } else {
            null
        }
        return ReplayController.create(core, ViewWalker(policy, compose), mainHandler)
    }
}
