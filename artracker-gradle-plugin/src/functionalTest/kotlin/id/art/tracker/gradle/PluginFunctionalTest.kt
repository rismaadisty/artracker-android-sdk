package id.art.tracker.gradle

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PluginFunctionalTest {
    private class Hit(val path: String, val query: String?, val key: String?)

    @TempDir lateinit var project: File
    private val hits = CopyOnWriteArrayList<Hit>()
    private var status = 201
    private lateinit var server: HttpServer
    private val secret = "art_sec_functional_secret_value"

    private val apiUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @BeforeEach fun start() {
        assumeTrue(System.getenv("ANDROID_HOME") != null, "ANDROID_HOME is not set")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            hits += Hit(ex.requestURI.rawPath, ex.requestURI.rawQuery, ex.requestHeaders.getFirst("X-Artracker-Key"))
            val body = if (status >= 400) "server said no".toByteArray() else ByteArray(0)
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
            ex.close()
        }
        server.start()
    }

    @AfterEach fun stop() { if (::server.isInitialized) server.stop(0) }

    private fun writeFixture(artrackerBlock: String) {
        val pluginDir = System.getProperty("artracker.pluginDir")
        File(project, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                includeBuild("${pluginDir.replace("\\", "/")}")
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        File(project, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application") version "9.0.1"
                id("id.art.tracker.symbols")
            }
            android {
                namespace = "fixture.app"
                compileSdk = 34
                defaultConfig { applicationId = "fixture.app"; minSdk = 24; targetSdk = 34; versionCode = 7; versionName = "1.0" }
                flavorDimensions += "tier"
                productFlavors {
                    create("free") { dimension = "tier"; versionName = "1.0-free" }
                    create("paid") { dimension = "tier"; versionName = "1.0-paid" }
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                    }
                }
            }
            $artrackerBlock
            """.trimIndent(),
        )
        File(project, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g\nandroid.useAndroidX=true\n")
        File(project, "src/main").mkdirs()
        File(project, "src/main/AndroidManifest.xml").writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="fixture"/></manifest>""",
        )
        File(project, "src/main/java/fixture/app").mkdirs()
        File(project, "src/main/java/fixture/app/Main.java").writeText(
            "package fixture.app; public class Main { public static String hello() { return \"hi\"; } }",
        )
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create().withProjectDir(project).withArguments(*args, "--stacktrace")

    private fun configured(extra: String = "") =
        """artracker { apiUrl = "$apiUrl"; secretKey = "$secret"; $extra }"""

    @Test fun `assembleFreeRelease uploads under 1_0-free`() {
        writeFixture(configured())
        val result: BuildResult = runner("assembleFreeRelease", "--configuration-cache").build()
        assertTrue("Artracker: mapping for 1.0-free uploaded" in result.output, result.output)
        assertEquals(1, hits.size)
        assertEquals("/api/v1/sdk/releases/1.0-free/proguard", hits[0].path)
        assertEquals("build=7&file=mapping.txt", hits[0].query)
        assertEquals(secret, hits[0].key)
    }

    @Test fun `assembleFreeDebug registers no upload task`() {
        writeFixture(configured())
        val tasks = runner("tasks", "--all").build().output
        assertFalse("uploadArtrackerFreeDebugMapping" in tasks, tasks)
        assertTrue("uploadArtrackerFreeReleaseMapping" in tasks, tasks)
        assertTrue("uploadArtrackerPaidReleaseMapping" in tasks, tasks)
    }

    @Test fun `missing key warns and build succeeds`() {
        writeFixture("""artracker { apiUrl = "$apiUrl" }""")
        val result = runner("assembleFreeRelease").build()
        assertTrue("skipping mapping upload" in result.output, result.output)
        assertTrue("BUILD SUCCESSFUL" in result.output, result.output)
        assertEquals(0, hits.size)
    }

    @Test fun `public key warns and skips`() {
        writeFixture("""artracker { apiUrl = "$apiUrl"; secretKey = "art_pub_nope" }""")
        val result = runner("assembleFreeRelease").build()
        assertTrue("skipping mapping upload" in result.output, result.output)
        assertEquals(0, hits.size)
    }

    @Test fun `server 500 warns by default and fails when failOnUploadError`() {
        status = 500
        writeFixture(configured())
        val warned = runner("assembleFreeRelease").build()
        assertTrue("500" in warned.output && "server said no" in warned.output, warned.output)
        assertTrue("BUILD SUCCESSFUL" in warned.output, warned.output)

        writeFixture(configured("failOnUploadError = true"))
        val failed = runner("assembleFreeRelease").buildAndFail()
        assertTrue("500" in failed.output, failed.output)
    }

    @Test fun `uploadMapping=false registers no task`() {
        writeFixture(configured("uploadMapping = false"))
        val tasks = runner("tasks", "--all").build().output
        assertFalse("uploadArtracker" in tasks, tasks)
    }

    @Test fun `secret key never appears in build output`() {
        writeFixture(configured())
        val result = runner("assembleFreeRelease", "--info").build()
        assertFalse(secret in result.output, "the secret key leaked into the build output")
    }
}
