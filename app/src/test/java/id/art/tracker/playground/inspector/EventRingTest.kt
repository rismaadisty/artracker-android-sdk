package id.art.tracker.playground.inspector

import id.art.tracker.playground.settings.EndpointProfile
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EventRingTest {
    @Test
    fun capacityIsEnforcedNewestFirst() {
        val ring = EventRing()
        repeat(250) { ring.add(inspected("e$it")) }
        val list = ring.items.value
        assertEquals(200, list.size)
        assertEquals("e249", list.first().name)
        assertEquals("e50", list.last().name)
        assertEquals(250L, ring.totalSeen.value)
    }

    @Test
    fun addAllKeepsBatchOrderWithTheLastAsNewest() {
        val ring = EventRing()
        ring.addAll(listOf(inspected("a"), inspected("b"), inspected("c")))
        assertEquals(listOf("c", "b", "a"), ring.items.value.map { it.name })
    }

    @Test
    fun concurrentAddsStayBounded() {
        val ring = EventRing()
        val start = CountDownLatch(1)
        val threads = List(8) { t ->
            thread {
                start.await()
                repeat(1_000) { ring.add(inspected("t$t-$it")) }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(200, ring.items.value.size)
        assertEquals(8_000L, ring.totalSeen.value)
    }

    @Test
    fun clearEmptiesTheListButKeepsTotalSeen() {
        val ring = EventRing()
        repeat(5) { ring.add(inspected("e$it")) }
        ring.clear()
        assertEquals(0, ring.items.value.size)
        assertEquals(5L, ring.totalSeen.value)
    }

    @Test
    fun smallCapacityRingDropsTheOldest() {
        val ring = EventRing(capacity = 3)
        repeat(5) { ring.add(inspected("e$it")) }
        assertEquals(listOf("e4", "e3", "e2"), ring.items.value.map { it.name })
    }

    @Test
    fun sinkProfileHasNoBeforeSendAndTheOthersShareTheMetadataTap() {
        assertNull(Inspector.beforeSendFor(EndpointProfile.SINK))
        val offline = Inspector.beforeSendFor(EndpointProfile.OFFLINE)
        assertNotNull(offline)
        assertSame(offline, Inspector.beforeSendFor(EndpointProfile.LOCAL))
        assertSame(offline, Inspector.beforeSendFor(EndpointProfile.BACKEND))
    }
}
