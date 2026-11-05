package id.art.tracker.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassAvailabilityTest {
    @After
    fun tearDown() {
        ClassAvailability.resetOverrides()
    }

    @Test
    fun missingClass_isFalse() {
        assertFalse(ClassAvailability.has("does.not.Exist"))
        assertTrue(ClassAvailability.has("java.lang.String"))
    }

    @Test
    fun overrides_forceResultAndResetRestores() {
        ClassAvailability.overrides["java.lang.String"] = false
        ClassAvailability.overrides["does.not.Exist"] = true
        assertFalse(ClassAvailability.has("java.lang.String"))
        assertTrue(ClassAvailability.has("does.not.Exist"))

        ClassAvailability.resetOverrides()
        assertTrue(ClassAvailability.has("java.lang.String"))
        assertFalse(ClassAvailability.has("does.not.Exist"))
    }

    @Test
    fun namedProbes_respectOverrides() {
        ClassAvailability.overrides["okhttp3.OkHttpClient"] = false
        assertFalse(ClassAvailability.okHttp)
        ClassAvailability.overrides["androidx.navigation.NavController"] = true
        assertTrue(ClassAvailability.navigation)
        ClassAvailability.overrides["androidx.fragment.app.FragmentActivity"] = false
        assertFalse(ClassAvailability.fragment)
        ClassAvailability.overrides["androidx.compose.ui.platform.AndroidComposeView"] = false
        assertFalse(ClassAvailability.compose)
    }

    @Test
    fun doesNotInitializeClass() {
        ClassAvailabilityProbe.initialized = false
        assertTrue(ClassAvailability.has(ClassAvailabilityProbe::class.java.name))
        assertFalse(ClassAvailabilityProbe.initialized)
    }
}

internal object ClassAvailabilityProbe {
    @Volatile
    var initialized: Boolean = false

    init {
        initialized = true
    }
}
