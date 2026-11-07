package id.art.tracker.playground.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsEditorTest {
    private val remote = "https://artracker.exlm.my.id/"
    private val local = "http://10.0.2.2:3000"

    private fun editor(
        initial: PlaygroundSettings = PlaygroundSettings.DEFAULT,
        buildEndpoint: String = remote,
        realKeyPresent: Boolean = true,
    ) = SettingsEditor(initial, buildEndpoint, realKeyPresent)

    @Test
    fun canApplyIsFalseWhenUnchanged() {
        assertFalse(editor().canApply)
    }

    @Test
    fun changedValidDraftCanBeApplied() {
        val e = editor()
        e.update { it.copy(environment = "qa") }
        assertTrue(e.canApply)
    }

    @Test
    fun invalidDraftBlocksApply() {
        val e = editor()
        e.update { it.copy(flushIntervalMs = 10) }
        assertFalse(e.canApply)
        assertEquals(1, e.errors.size)
    }

    @Test
    fun presetReplacesTheDraftButNotTheProfile() {
        val e = editor(PlaygroundSettings.DEFAULT.copy(endpointProfile = EndpointProfile.OFFLINE))
        e.applyPreset(SettingsPreset.PRIVACY_STRICT)
        assertTrue(e.draft.maskAllText)
        assertEquals(EndpointProfile.OFFLINE, e.draft.endpointProfile)
    }

    @Test
    fun sinkOfflineAndLocalNeverRequireConfirmation() {
        for (p in listOf(EndpointProfile.SINK, EndpointProfile.OFFLINE, EndpointProfile.LOCAL)) {
            val e = editor()
            e.update { it.copy(endpointProfile = p) }
            assertFalse("$p", e.requiresBackendConfirmation)
        }
    }

    @Test
    fun backendWithARemoteBuildEndpointRequiresConfirmation() {
        val e = editor()
        e.update { it.copy(endpointProfile = EndpointProfile.BACKEND) }
        assertTrue(e.requiresBackendConfirmation)
        assertEquals("artracker.exlm.my.id", e.confirmationHost)
        assertTrue(e.canApply)
    }

    @Test
    fun backendWithALocalBuildEndpointDoesNot() {
        val e = editor(buildEndpoint = local)
        e.update { it.copy(endpointProfile = EndpointProfile.BACKEND) }
        assertFalse(e.requiresBackendConfirmation)
    }

    @Test
    fun backendWithoutARealKeyCannotBeApplied() {
        val e = editor(realKeyPresent = false)
        e.update { it.copy(endpointProfile = EndpointProfile.BACKEND) }
        assertFalse(e.canApply)
        assertTrue(e.errors.any { it.contains("Kunci API") })
    }

    @Test
    fun localWithARemoteHostCannotBeApplied() {
        val e = editor()
        e.update { it.copy(endpointProfile = EndpointProfile.LOCAL, localEndpoint = remote) }
        assertFalse(e.canApply)
    }

    @Test
    fun typedPhraseIsExactAndCaseSensitive() {
        val e = editor()
        assertTrue(e.confirmationAccepted("KIRIM"))
        assertTrue(e.confirmationAccepted(" KIRIM "))
        for (bad in listOf("kirim", "Kirim", "KIRIM!", "KIRIMKAN", "KIRIM KIRIM", "")) {
            assertFalse(bad, e.confirmationAccepted(bad))
        }
    }
}
