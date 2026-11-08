package id.art.tracker.transport

import id.art.tracker.testutil.InMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportConfigStoreTest {
    private val kv = InMemoryStore()
    private val store = TransportConfigStore(kv)

    @Test
    fun load_withoutSave_isNull() {
        assertNull(store.load())
    }

    @Test
    fun saveThenLoad_roundTrips() {
        store.save("https://ingest.example.com", "art_live_" + "a".repeat(32), "staging", "1.2.3")
        assertEquals(
            TransportConfigStore.Saved("https://ingest.example.com", "art_live_" + "a".repeat(32), "staging", "1.2.3"),
            TransportConfigStore(kv).load(),
        )
    }

    @Test
    fun save_overwritesPrevious() {
        store.save("https://a.example.com", "art_live_k1", "production", "1")
        store.save("https://b.example.com", "art_live_k2", "staging", "2")
        assertEquals("https://b.example.com", store.load()?.endpoint)
        assertEquals("art_live_k2", store.load()?.apiKey)
    }

    @Test
    fun blankEndpointOrKey_isTreatedAsMissing() {
        store.save("", "art_live_k", "production", "1")
        assertNull(store.load())
        store.save("https://a.example.com", " ", "production", "1")
        assertNull(store.load())
    }
}
