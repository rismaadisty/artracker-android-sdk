package id.art.tracker.playground.labs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyCasesTest {
    private val expectedIds = listOf(
        "compose_password", "compose_email_default", "compose_numeric_default", "compose_mask_modifier",
        "compose_unmask_modifier", "compose_unmask_inside_masked", "compose_plain_text_email",
        "view_password", "view_numeric", "view_tag_mask", "view_autofill_hint_card",
        "webview_blocked", "imageview_placeholder", "popup_window", "dropdown_menu", "max_all_text_toggle",
    )

    private fun case(id: String) = PrivacyCases.all.first { it.id == id }

    @Test
    fun idsAreExactlyTheDocumentedOnesAndUnique() {
        assertEquals(expectedIds, PrivacyCases.all.map { it.id })
        assertEquals(expectedIds.size, PrivacyCases.all.map { it.id }.toSet().size)
    }

    @Test
    fun everyCaseIsDescribed() {
        PrivacyCases.all.forEach {
            assertTrue(it.id, it.title.isNotBlank())
            assertTrue(it.id, it.description.isNotBlank())
        }
    }

    @Test
    fun knownLimitationsAreOnesTheReadmeActuallyLists() {
        val readme = File("../README.md").readText()
        val section = readme.substringAfter("## Known limitations").substringBefore("\n## ")
        assertTrue("README section not found", section.length > 500)
        val leaks = PrivacyCases.all.filter { it.expectation == Expectation.LEAKS_KNOWN_LIMITATION }
        assertTrue(leaks.isNotEmpty())
        leaks.forEach {
            assertNotNull("${it.id} needs a limitationRef", it.limitationRef)
            assertTrue("${it.id}: '${it.limitationRef}' is not in the README limitations", section.contains(it.limitationRef!!))
        }
    }

    @Test
    fun theKnownLeaksAreMarkedAsSuch() {
        listOf("popup_window", "compose_unmask_inside_masked", "compose_plain_text_email").forEach {
            assertEquals(it, Expectation.LEAKS_KNOWN_LIMITATION, case(it).expectation)
        }
    }

    @Test
    fun blockedAndMaskedExpectations() {
        assertEquals(Expectation.BLOCKED, case("webview_blocked").expectation)
        assertEquals(Expectation.MASKED, case("compose_password").expectation)
        assertEquals(Expectation.MASKED, case("view_password").expectation)
    }
}
