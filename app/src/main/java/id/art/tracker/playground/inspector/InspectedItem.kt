package id.art.tracker.playground.inspector

/**
 * Where an [InspectedItem] came from. [SENT_PAYLOAD] is what the SDK actually sent (already redacted); [METADATA_ONLY]
 * is a value-free description taken before redaction, so it never carries a value.
 */
enum class ItemSource { SENT_PAYLOAD, METADATA_ONLY }

data class InspectedItem(
    val id: String,
    val type: String,
    val name: String,
    val level: String?,
    val message: String?,
    val timestampMs: Long,
    val sessionId: String,
    val userId: String?,
    val attributes: Map<String, String>,
    val truncated: Boolean,
    val source: ItemSource,
)
