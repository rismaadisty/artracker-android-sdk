package id.art.tracker.playground.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Draft state of the Settings screen: validation, presets and the typed backend confirmation. */
class SettingsEditor(
    private val initial: PlaygroundSettings,
    private val buildEndpoint: String,
    private val realKeyPresent: Boolean,
) {
    var draft: PlaygroundSettings by mutableStateOf(initial)
        private set

    val errors: List<String>
        get() = draft.validate() +
            if (draft.endpointProfile == EndpointProfile.BACKEND && !realKeyPresent) {
                listOf("Kunci API belum diatur (artracker.key di local.properties)")
            } else {
                emptyList()
            }

    val canApply: Boolean get() = errors.isEmpty() && draft != initial

    /** A REMOTE endpoint needs the typed phrase; the other profiles never do. */
    val requiresBackendConfirmation: Boolean
        get() = draft.endpointProfile == EndpointProfile.BACKEND && draft.isRemote(buildEndpoint)

    /** Host shown in the confirmation dialog (never credentials, port or path). */
    val confirmationHost: String get() = EndpointGuard.hostOf(buildEndpoint)

    fun confirmationAccepted(typed: String): Boolean = typed.trim() == CONFIRM_PHRASE

    fun update(transform: (PlaygroundSettings) -> PlaygroundSettings) {
        draft = transform(draft)
    }

    fun applyPreset(preset: SettingsPreset) {
        draft = preset.apply(draft)
    }

    companion object {
        const val CONFIRM_PHRASE = "KIRIM"
    }
}
