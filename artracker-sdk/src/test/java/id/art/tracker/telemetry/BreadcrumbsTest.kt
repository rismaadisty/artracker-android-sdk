package id.art.tracker.telemetry

import id.art.tracker.testutil.FakeClock
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbsTest {
    @Test
    fun ringBuffer_keepsLast30() {
        val b = Breadcrumbs()
        for (i in 0 until 100) b.add("event", "m$i")

        val snap = b.snapshot()

        assertEquals(30, snap.size)
        assertEquals((70 until 100).map { "m$it" }, snap.map { it["message"] })
    }

    @Test
    fun snapshot_hasTsTypeMessageData_andIsIsoUtc() {
        val b = Breadcrumbs(clock = FakeClock(1_762_331_598_050))
        b.add("screen", "Home", mapOf("previous" to "Login", "n" to 3, "ok" to true, "obj" to listOf(1)))

        val e = b.snapshot().single()

        assertEquals("2025-11-05T08:33:18.050Z", e["ts"])
        assertEquals("screen", e["type"])
        assertEquals("Home", e["message"])
        assertEquals(mapOf("previous" to "Login", "n" to 3, "ok" to true, "obj" to "[1]"), e["data"])
    }

    @Test
    fun fewerThanCapacity_returnsAllInOrder() {
        val b = Breadcrumbs(capacity = 5)
        b.add("a", "1")
        b.add("a", "2")
        assertEquals(listOf("1", "2"), b.snapshot().map { it["message"] })
    }

    @Test
    fun oversizedValues_areClipped() {
        val b = Breadcrumbs()
        b.add("t", "x".repeat(5_000), mapOf("k" to "y".repeat(5_000)))
        val e = b.snapshot().single()
        assertEquals(200, (e["message"] as String).length)
        assertEquals(200, ((e["data"] as Map<*, *>)["k"] as String).length)
    }

    @Test
    fun concurrentAdds_neverExceedCapacity_andStayOrdered() {
        val b = Breadcrumbs()
        val start = CountDownLatch(1)
        val threads = (0 until 4).map { t ->
            thread {
                start.await()
                for (i in 0 until 500) b.add("e", "t$t-$i")
            }
        }
        start.countDown()
        threads.forEach { it.join() }

        val snap = b.snapshot()
        assertEquals(30, snap.size)
        // Per thread, entries keep their relative order.
        snap.groupBy { (it["message"] as String).substringBefore('-') }.values.forEach { group ->
            val idx = group.map { (it["message"] as String).substringAfter('-').toInt() }
            assertTrue(idx == idx.sorted())
        }
    }
}
