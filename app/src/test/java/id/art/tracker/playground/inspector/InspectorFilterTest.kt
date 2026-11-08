package id.art.tracker.playground.inspector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorFilterTest {
    private val login = inspected("login_clicked", attributes = mapOf("plan" to "Pro"))
    private val http = inspected("http_request", type = "log", message = "GET /status/404")

    @Test
    fun emptyFilterMatchesEverything() {
        val f = InspectorFilter()
        assertTrue(f.matches(login))
        assertTrue(f.matches(http))
    }

    @Test
    fun typeFilterKeepsOnlyTheSelectedTypes() {
        val f = InspectorFilter(types = setOf("log"))
        assertFalse(f.matches(login))
        assertTrue(f.matches(http))
    }

    @Test
    fun queryIsCaseInsensitiveOverNameMessageAndAttributes() {
        assertTrue(InspectorFilter(query = "LOGIN").matches(login))
        assertTrue(InspectorFilter(query = "status/404").matches(http))
        assertTrue(InspectorFilter(query = "plan").matches(login))
        assertTrue(InspectorFilter(query = "pro").matches(login))
        assertFalse(InspectorFilter(query = "zzz").matches(login))
    }

    @Test
    fun typeAndQueryCombine() {
        val f = InspectorFilter(types = setOf("log"), query = "login")
        assertFalse(f.matches(login))
        assertFalse(f.matches(http))
    }
}
