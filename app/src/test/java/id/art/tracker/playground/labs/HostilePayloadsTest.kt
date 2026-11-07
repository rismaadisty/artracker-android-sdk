package id.art.tracker.playground.labs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostilePayloadsTest {
    private fun payload(name: String) = HostilePayloads.all.first { it.name == name }.properties

    @Test
    fun containsTheNamedPayloads() {
        val names = HostilePayloads.all.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("deep_nesting_40", "huge_string_10000", "unicode_and_lone_surrogate", "sensitive_keys", "sensitive_values")))
    }

    @Test
    fun deepNestingReallyNestsFortyMapLevels() {
        var depth = 0
        var current: Any? = payload("deep_nesting_40")
        while (current is Map<*, *>) {
            depth++
            current = current.values.firstOrNull { it is Map<*, *> }
        }
        assertEquals(40, depth)
    }

    @Test
    fun hugeStringHasATenThousandCharValue() {
        assertTrue(payload("huge_string_10000").values.any { it is String && it.length == 10_000 })
    }

    @Test
    fun unicodePayloadCarriesALoneSurrogate() {
        assertTrue(payload("unicode_and_lone_surrogate").values.any { it is String && it.contains('\uD800') })
    }

    @Test
    fun sensitiveKeysHavePasswordAndToken() {
        val keys = payload("sensitive_keys").keys
        assertTrue(keys.contains("password") && keys.contains("token"))
    }

    @Test
    fun sensitiveValuesExerciseTheRedactorAndAreRecognisablyFake() {
        val values = payload("sensitive_values").values.map { it.toString() }
        assertTrue(values.any { it.endsWith("@example.invalid") })
        assertTrue(values.any { it == "4111111111111111" })
        assertTrue(values.any { it.startsWith("eyJ") && it.contains("FAKE") })
        assertTrue(values.any { it.startsWith("Bearer ") && it.contains("FAKE") })
    }
}
