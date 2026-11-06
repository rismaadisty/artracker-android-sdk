package id.art.tracker.replay.capture

import androidx.annotation.VisibleForTesting
import id.art.tracker.privacy.MaskingPolicy
import java.util.concurrent.atomic.AtomicInteger

/**
 * The only place that instantiates [ComposeWalker], which links Compose types (a `compileOnly` dependency). Call it
 * ONLY after `ClassAvailability.compose` said yes: this small class is then the first one to touch Compose, so
 * neither the replay controller nor `ARTrackerCore` ever link a Compose type.
 */
internal object ComposeWalkerFactory {
    @VisibleForTesting
    internal val createCalls = AtomicInteger()

    fun create(policy: MaskingPolicy): ComposeWalkerApi {
        createCalls.incrementAndGet()
        return ComposeWalker(policy)
    }
}
