package id.art.tracker.playground.labs

data class NamedPayload(val name: String, val properties: Map<String, Any?>)

/**
 * Inputs that stress the SDK's JSON encoder and redactor. Every sensitive-looking value is recognisably fake
 * (`.invalid` domain, `FAKE` markers, a published test card number), and sending them is guarded by `LabGuard`.
 */
object HostilePayloads {
    private fun nested(levels: Int): Map<String, Any?> {
        var current: Map<String, Any?> = mapOf("leaf" to "x")
        repeat(levels - 1) { current = mapOf("n" to current) }
        return current
    }

    val all: List<NamedPayload> = listOf(
        NamedPayload("deep_nesting_40", nested(40)),
        NamedPayload("huge_string_10000", mapOf("big" to "a".repeat(10_000))),
        NamedPayload("unicode_and_lone_surrogate", mapOf("emoji" to "😀", "lone" to "\uD800", "rtl" to "שלום")),
        NamedPayload("sensitive_keys", mapOf("password" to "FAKE-password-123", "token" to "FAKE-token-123")),
        NamedPayload(
            "sensitive_values",
            mapOf(
                "email" to "fake.user@example.invalid",
                "card" to "4111111111111111",
                "jwt" to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJGQUtFIn0.FAKEsignature",
                "auth" to "Bearer FAKE.token.value",
            ),
        ),
    )
}
