package id.art.tracker.appstart

import id.art.tracker.core.Json
import id.art.tracker.core.SdkInfo
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** The body of `POST /api/v1/app-start/profiles`: `{sdk, context, profile}`. */
internal object ProfilePayload {
    fun json(
        startId: String,
        intervalMs: Long,
        appPackages: List<String>,
        buffer: StackSampleBuffer,
        appVersion: String,
        build: String?,
        environment: String,
    ): String {
        val context = linkedMapOf<String, Any?>("app_version" to appVersion)
        build?.let { context["build"] = it }
        context["environment"] = environment
        val profile = linkedMapOf<String, Any?>(
            "v" to 1,
            "start_id" to startId,
            "platform" to "android",
            "app_packages" to appPackages,
            "interval_ms" to intervalMs,
            "t0" to "process_start",
            "frames" to buffer.frames(),
            "stacks" to buffer.stacks(),
            "samples" to buffer.samples(),
        )
        val root = linkedMapOf<String, Any?>(
            "sdk" to linkedMapOf("name" to SdkInfo.NAME, "version" to SdkInfo.VERSION),
            "context" to context,
            "profile" to profile,
        )
        return Json.encode(root)
    }

    fun gzip(json: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return bytes.toByteArray()
    }
}
