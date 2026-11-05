package id.art.tracker.transport

import id.art.tracker.core.Iso8601
import id.art.tracker.core.Json
import id.art.tracker.core.RawJson
import id.art.tracker.core.SdkInfo
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

/**
 * Builds the ingest envelope `{sent_at, sdk, context, items}`. Each item's `payloadJson` is already
 * final JSON and is embedded verbatim, never re-parsed.
 */
internal class PayloadEncoder(private val context: () -> Map<String, Any?>) {

    fun envelopeJson(items: List<StoredItem>, sentAtMs: Long): String =
        StringBuilder().also { writeEnvelope(items, sentAtMs, it) }.toString()

    /** Streams the envelope through UTF-8 into gzip without building an intermediate String. */
    fun encodeGzip(items: List<StoredItem>, sentAtMs: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        OutputStreamWriter(GZIPOutputStream(bytes), Charsets.UTF_8).use { writer ->
            writeEnvelope(items, sentAtMs, writer)
        }
        return bytes.toByteArray()
    }

    private fun writeEnvelope(items: List<StoredItem>, sentAtMs: Long, out: Appendable) {
        out.append("{\"sent_at\":")
        Json.write(Iso8601.format(sentAtMs), out)
        out.append(",\"sdk\":")
        Json.write(mapOf("name" to SdkInfo.NAME, "version" to SdkInfo.VERSION), out)
        out.append(",\"context\":")
        Json.write(context(), out)
        out.append(",\"items\":[")
        items.forEachIndexed { index, item ->
            if (index > 0) out.append(',')
            Json.write(RawJson(item.payloadJson), out)
        }
        out.append("]}")
    }
}
