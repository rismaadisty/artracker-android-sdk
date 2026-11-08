package id.art.tracker.playground.scenario

import android.app.Activity
import android.graphics.PointF
import kotlinx.coroutines.delay

/**
 * The real [ScenarioContext]. Everything that touches the outside world is injected so the failure paths (missing target,
 * target in another window, no Activity, replay not recording) are unit-tested.
 */
class AppScenarioContext(
    private val proves: Set<Capability>,
    private val navigateTo: (String) -> Unit,
    private val activity: () -> Activity?,
    private val replayOn: () -> Boolean,
    private val replayFps: () -> Double,
    private val userGate: suspend (String) -> Unit,
    private val notes: (String) -> Unit = {},
    private val registry: TapRegistry = TapRegistry.Shared,
    private val recorderInstalled: (Activity) -> Boolean = SyntheticTouch::isRecorderInstalled,
    private val touch: suspend (Activity, PointF) -> Unit = { a, p -> SyntheticTouch.tap(a, p) },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val tapWaitMs: Long = 2_000,
) : ScenarioContext {
    override suspend fun navigate(route: String) {
        navigateTo(route)
        sleep(NAV_SETTLE_MS)
    }

    override suspend fun tap(targetId: String) {
        val deadline = System.nanoTime() + tapWaitMs * 1_000_000
        var target = registry.find(targetId)
        while (target == null && System.nanoTime() < deadline) {
            sleep(POLL_MS)
            target = registry.find(targetId)
        }
        if (target == null) throw IllegalStateException("target '$targetId' tidak ditemukan di layar")
        if (!target.inActivityWindow) {
            throw IllegalStateException("target '$targetId' ada di window lain (dialog/popup): sentuhan di sana tidak direkam")
        }
        val current = activity() ?: throw IllegalStateException("tidak ada Activity yang aktif untuk menerima sentuhan")
        val center = target.boundsInWindow.center
        touch(current, PointF(center.x, center.y))
    }

    override suspend fun type(fieldId: String, text: String) {
        if (!ScenarioFields.set(fieldId, text)) throw IllegalStateException("kolom '$fieldId' tidak ditemukan di layar")
        sleep(TYPE_SETTLE_MS)
    }

    override suspend fun awaitUser(prompt: String) = userGate(prompt)

    override suspend fun awaitReplayFrame() = sleep(ReplayReadiness.warmupMs(replayFps()))

    override fun preflight(): List<String> {
        val warnings = mutableListOf<String>()
        val current = activity()
        if (current == null) warnings += "tidak ada Activity yang aktif: sentuhan sintetis tidak bisa dikirim"
        if (!replayOn()) {
            warnings += "replay tidak merekam: sentuhan sintetis tidak akan terekam di replay"
        } else if (current != null && !recorderInstalled(current)) {
            warnings += "perekam sentuhan SDK belum terpasang di window ini (replay belum berjalan?)"
        }
        if (Capability.REPLAY in proves) warnings += "sentuhan di dialog/popup tidak direkam (SDK hanya membungkus window Activity)"
        return warnings
    }

    override fun note(message: String) = notes(message)

    private companion object {
        const val NAV_SETTLE_MS = 500L
        const val TYPE_SETTLE_MS = 200L
        const val POLL_MS = 50L
    }
}
