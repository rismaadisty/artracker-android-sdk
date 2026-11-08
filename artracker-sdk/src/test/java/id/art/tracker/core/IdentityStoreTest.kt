package id.art.tracker.core

import id.art.tracker.testutil.InMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityStoreTest {
    @Test
    fun anonymousId_stable() {
        val store = InMemoryStore()
        val a = IdentityStore(store)
        assertEquals(a.anonymousId, a.anonymousId)
        assertEquals(a.anonymousId, IdentityStore(store).anonymousId)
    }

    @Test
    fun reset_clearsUserAndRegeneratesAnonymousId() {
        val store = InMemoryStore()
        val a = IdentityStore(store)
        val before = a.anonymousId
        a.userId = "u1"
        a.traits = mapOf("plan" to "pro")

        a.reset()

        assertNull(a.userId)
        assertTrue(a.traits.isEmpty())
        assertNotEquals(before, a.anonymousId)
        assertEquals(a.anonymousId, IdentityStore(store).anonymousId)
        assertNull(IdentityStore(store).userId)
    }

    @Test
    fun userId_persists_traitsDoNot() {
        val store = InMemoryStore()
        val a = IdentityStore(store)
        a.userId = "u1"
        a.traits = mapOf("k" to 1)
        val b = IdentityStore(store)
        assertEquals("u1", b.userId)
        assertTrue(b.traits.isEmpty())
    }
}
