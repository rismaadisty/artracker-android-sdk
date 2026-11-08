package id.art.tracker.playground

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessInfoTest {
    @Test
    fun mainProcessIsTheOneNamedAfterThePackage() {
        assertTrue(isMainProcess("id.art.tracker", "id.art.tracker"))
    }

    @Test
    fun secondaryProcessIsNotMain() {
        assertFalse(isMainProcess("id.art.tracker:restart", "id.art.tracker"))
    }

    @Test
    fun unknownProcessNameFailsClosed() {
        assertFalse(isMainProcess(null, "id.art.tracker"))
    }

    @Test
    fun lookalikeProcessIsNotMain() {
        assertFalse(isMainProcess("id.art.tracker.other", "id.art.tracker"))
    }
}
