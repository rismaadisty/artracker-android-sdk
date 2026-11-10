package id.art.tracker.replay

import id.art.tracker.core.KeyValueStore

/** What `GET /api/v1/replay/config` answers: the owner's switch for screenshot replay and an fps ceiling. */
internal class RemoteReplayConfig(
    val screenshotEnabled: Boolean,
    /** Server cap on the capture rate; null = no cap. */
    val maxFps: Double?,
)

/**
 * The last known [RemoteReplayConfig], persisted so a restart can start in the right mode before the network
 * answers. `screenshotEnabled` is null until the first answer arrives (unknown is not the same as disabled).
 */
internal class ReplayRemoteConfigCache(private val store: KeyValueStore) {
    val screenshotEnabled: Boolean?
        get() = when (store.getLong(KEY_ENABLED, UNKNOWN)) {
            1L -> true
            0L -> false
            else -> null
        }

    val maxFps: Double?
        get() = store.getLong(KEY_MAX_FPS_X100, UNKNOWN).takeIf { it > 0 }?.let { it / 100.0 }

    fun save(config: RemoteReplayConfig) {
        store.putLong(KEY_ENABLED, if (config.screenshotEnabled) 1L else 0L)
        store.putLong(KEY_MAX_FPS_X100, config.maxFps?.takeIf { it.isFinite() && it > 0 }?.let { Math.round(it * 100) } ?: UNKNOWN)
    }

    /** A `403 screenshot_disabled` is as good as a fresh config answer saying so. */
    fun markScreenshotDisabled() {
        store.putLong(KEY_ENABLED, 0L)
    }

    private companion object {
        const val KEY_ENABLED = "replay_screenshot_enabled"
        const val KEY_MAX_FPS_X100 = "replay_max_fps_x100"
        const val UNKNOWN = -1L
    }
}
