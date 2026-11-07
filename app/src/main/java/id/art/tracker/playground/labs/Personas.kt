package id.art.tracker.playground.labs

data class Persona(val userId: String, val label: String, val traits: Map<String, Any?>)

object Personas {
    val all: List<Persona> = listOf(
        Persona("persona-alice", "Alice (pro)", mapOf("plan" to "pro", "country" to "ID")),
        Persona("persona-budi", "Budi (free)", mapOf("plan" to "free", "country" to "ID")),
        Persona("persona-citra", "Citra (trial)", mapOf("plan" to "trial", "country" to "SG")),
    )
}
