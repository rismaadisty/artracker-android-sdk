package id.art.tracker.playground.ui

sealed class Route(val path: String) {
    object Skenario : Route("skenario")
    object Lab : Route("lab")
    object Inspector : Route("inspector")
    object Pengaturan : Route("pengaturan")
    object LabDetail : Route("lab/{id}") {
        const val ARG = "id"
        fun of(id: String) = "lab/$id"
    }

    companion object {
        // A getter, not a property: the objects extend Route, so a stored list would read them mid-initialisation (null).
        val topLevel: List<Route> get() = listOf(Skenario, Lab, Inspector, Pengaturan)
    }
}
