package id.art.tracker.gradle

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MappingUploaderTest {
    private class Recorded(val method: String, val rawPath: String, val rawQuery: String?, val headers: Map<String, String>, val body: ByteArray)

    @TempDir lateinit var tmp: File
    private val seen = AtomicReference<Recorded?>()
    private var server: HttpServer? = null

    @AfterEach fun stop() { server?.stop(0) }

    private fun serve(status: Int, responseBody: String = ""): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            seen.set(Recorded(ex.requestMethod, ex.requestURI.rawPath, ex.requestURI.rawQuery, headers, ex.requestBody.readBytes()))
            val bytes = responseBody.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun mappingFile(text: String): File = File(tmp, "mapping.txt").also { it.writeText(text) }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    @Test fun `posts gzipped mapping with key and encoded version`() {
        val url = serve(201)
        val file = mappingFile("com.a -> b:")

        val result = MappingUploader().upload(url, "art_sec_x", "1.4.0+7", 42, file)

        assertEquals(UploadResult.Uploaded, result)
        val r = seen.get()!!
        assertEquals("POST", r.method)
        assertEquals("/api/v1/sdk/releases/1.4.0%2B7/proguard", r.rawPath)
        assertEquals("build=42&file=mapping.txt", r.rawQuery)
        assertEquals("art_sec_x", r.headers["x-artracker-key"])
        assertEquals("gzip", r.headers["content-encoding"])
        assertEquals("text/plain", r.headers["content-type"])
        assertArrayEquals(Files.readAllBytes(file.toPath()), gunzip(r.body))
    }

    @Test fun `spaces in version are encoded as percent 20`() {
        val url = serve(201)
        MappingUploader().upload(url, "art_sec_x", "2.0 beta", 1, mappingFile("a -> b:"))
        assertEquals("/api/v1/sdk/releases/2.0%20beta/proguard", seen.get()!!.rawPath)
    }

    @Test fun `200 unchanged maps to Unchanged`() {
        val url = serve(200)
        assertEquals(UploadResult.Unchanged, MappingUploader().upload(url, "art_sec_x", "1.0", 1, mappingFile("a -> b:")))
    }

    @Test fun `403 maps to Failed with status and body`() {
        val url = serve(403, "secret key required")
        val result = MappingUploader().upload(url, "art_pub_x", "1.0", 1, mappingFile("a -> b:"))
        val failed = assertInstanceOf(UploadResult.Failed::class.java, result)
        assertTrue("403" in failed.reason, failed.reason)
        assertTrue("secret key required" in failed.reason, failed.reason)
    }

    @Test fun `connection refused maps to Failed`() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = s.address.port
        s.start(); s.stop(0) // a port that is now closed
        val result = MappingUploader().upload("http://127.0.0.1:$port", "art_sec_x", "1.0", 1, mappingFile("a -> b:"))
        assertInstanceOf(UploadResult.Failed::class.java, result)
    }

    @Test fun `trailing slash in apiUrl is tolerated`() {
        val url = serve(201)
        MappingUploader().upload("$url/", "art_sec_x", "1.0", 1, mappingFile("a -> b:"))
        assertEquals("/api/v1/sdk/releases/1.0/proguard", seen.get()!!.rawPath)
    }

    @Test fun `missing mapping file maps to Failed without a request`() {
        val url = serve(201)
        val result = MappingUploader().upload(url, "art_sec_x", "1.0", 1, File(tmp, "nope.txt"))
        assertInstanceOf(UploadResult.Failed::class.java, result)
        assertEquals(null, seen.get())
    }

    @Test fun `failure reason never contains the secret key`() {
        val url = serve(500, "boom")
        val result = MappingUploader().upload(url, "art_sec_topsecret", "1.0", 1, mappingFile("a -> b:"))
        val failed = assertInstanceOf(UploadResult.Failed::class.java, result)
        assertTrue("topsecret" !in failed.reason)
    }

    @Test fun `a key with a control character fails without echoing the key and without a request`() {
        val url = serve(201)
        val result = MappingUploader().upload(url, "art_sec_abc\ndef", "1.0", 1, mappingFile("a -> b:"))
        val failed = assertInstanceOf(UploadResult.Failed::class.java, result)
        assertTrue("art_sec_abc" !in failed.reason && "def" !in failed.reason, failed.reason)
        assertEquals(null, seen.get())
    }

    @Test fun `a server that never answers fails within the request timeout`() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { Thread.sleep(10_000) }
        s.start()
        server = s
        val started = System.nanoTime()
        val result = MappingUploader(timeout = java.time.Duration.ofSeconds(1))
            .upload("http://127.0.0.1:${s.address.port}", "art_sec_x", "1.0", 1, mappingFile("a -> b:"))
        assertInstanceOf(UploadResult.Failed::class.java, result)
        assertTrue((System.nanoTime() - started) < 8_000_000_000L)
    }
}
