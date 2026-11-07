package id.art.tracker.playground.labs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTargetsTest {
    @Test
    fun fiveTargetsWithTheDocumentedPaths() {
        assertEquals(
            listOf("/status/200", "/status/404", "/status/500", "/delay/20", "/delay/1"),
            HttpTargets.all.map { it.path },
        )
        assertTrue(HttpTargets.all.all { it.label.isNotBlank() })
    }

    @Test
    fun urlOfJoinsBaseAndPath() {
        val t = HttpTargets.all.first { it.path == "/status/404" }
        assertEquals("http://localhost:38458/status/404", HttpTargets.urlOf("http://localhost:38458", t))
        assertEquals("http://localhost:38458/status/404", HttpTargets.urlOf("http://localhost:38458/", t))
    }

    @Test
    fun noAppSourceDependsOnAnExternalHttpService() {
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { it.readText().contains("httpbin") }
            .map { it.path }
            .toList()
        assertFalse("external host referenced in $offenders", offenders.isNotEmpty())
    }
}
