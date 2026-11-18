package id.art.tracker.gradle

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.zip.GZIPOutputStream

public sealed interface UploadResult {
    /** The server stored the mapping (HTTP 201). */
    public data object Uploaded : UploadResult

    /** The server already had this exact mapping (HTTP 200). */
    public data object Unchanged : UploadResult

    /** Anything else. [reason] carries the status and body or the I/O error, and never the secret key. */
    public data class Failed(val reason: String) : UploadResult
}

/**
 * Uploads a `mapping.txt` to `POST {apiUrl}/api/v1/sdk/releases/{version}/proguard?build={code}&file=mapping.txt` as a single
 * gzip body. It never throws for a network or server problem: the caller decides whether a [UploadResult.Failed] fails the build.
 */
public class MappingUploader(
    // A connect timeout keeps an unreachable host (packets dropped, no refusal) from blocking the build for the whole request timeout.
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val timeout: Duration = Duration.ofMinutes(5),
) {
    public fun upload(apiUrl: String, secretKey: String, versionName: String, versionCode: Long, mapping: File): UploadResult {
        // Checked first, and its text never goes into a message: the JDK echoes an invalid header value in its exception,
        // which would put the secret into the build log.
        if (secretKey.isEmpty() || !secretKey.all { it in '\u0021'..'\u007E' }) {
            return UploadResult.Failed("the secret key is empty or contains characters that are not allowed in an HTTP header")
        }
        if (!mapping.isFile) return UploadResult.Failed("mapping file not found: ${mapping.path}")

        val gz = Files.createTempFile("artracker-mapping-", ".txt.gz")
        try {
            mapping.inputStream().use { input -> GZIPOutputStream(Files.newOutputStream(gz)).use { input.copyTo(it) } }
            val request = HttpRequest.newBuilder(uri(apiUrl, versionName, versionCode))
                .timeout(timeout)
                .header("X-Artracker-Key", secretKey)
                .header("Content-Encoding", "gzip")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofFile(gz))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            return when (response.statusCode()) {
                201 -> UploadResult.Uploaded
                200 -> UploadResult.Unchanged
                else -> UploadResult.Failed("HTTP ${response.statusCode()}: ${response.body().take(MAX_BODY_IN_REASON)}")
            }
        } catch (e: IOException) {
            return UploadResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return UploadResult.Failed("interrupted")
        } catch (e: IllegalArgumentException) {
            // A malformed apiUrl: URI.create / HttpRequest.newBuilder reject it.
            return UploadResult.Failed("invalid apiUrl or request: ${e.message}")
        } finally {
            Files.deleteIfExists(gz)
        }
    }

    private fun uri(apiUrl: String, versionName: String, versionCode: Long): URI {
        // URLEncoder is form encoding: a space becomes '+', a literal '+' becomes %2B. A path segment wants %20 for the space.
        val version = URLEncoder.encode(versionName, StandardCharsets.UTF_8).replace("+", "%20")
        return URI.create("${apiUrl.trimEnd('/')}/api/v1/sdk/releases/$version/proguard?build=$versionCode&file=mapping.txt")
    }

    private companion object {
        const val MAX_BODY_IN_REASON = 500
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
