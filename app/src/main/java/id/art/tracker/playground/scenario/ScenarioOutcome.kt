package id.art.tracker.playground.scenario

object ScenarioOutcome {
    /** A one-line summary for the snackbar when a run ends (Done or Failed); null for states that are not an outcome. */
    fun message(state: RunState): String? = when (state) {
        is RunState.Failed -> "Skenario gagal di langkah ${state.index + 1}: ${state.message}"
        is RunState.Done ->
            if (state.warnings.isEmpty()) "Skenario selesai" else "Skenario selesai dengan ${state.warnings.size} peringatan: ${state.warnings.first()}"
        else -> null
    }
}
