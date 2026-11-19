package id.art.tracker.appstart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StackSampleBufferTest {
    private fun el(method: String, cls: String = "com.example.A", file: String? = "A.kt", line: Int = 10) =
        StackTraceElement(cls, method, file, line)

    private fun stack(vararg methods: String): Array<StackTraceElement> = methods.map { el(it) }.toTypedArray()

    @Test
    fun identicalStacks_shareOneStackAndFrames() {
        val buffer = StackSampleBuffer()
        repeat(3) { buffer.add(it * 10L, stack("leaf", "mid", "root")) }

        assertEquals(1, buffer.stacks().size)
        assertEquals(3, buffer.frames().size)
        assertEquals(listOf<Any?>(0, 0, 0), buffer.samples().map { it["s"] })
        assertEquals(listOf<Any?>(0L, 10L, 20L), buffer.samples().map { it["t"] })
    }

    @Test
    fun differentStacks_getDifferentIndexes_andShareFrames() {
        val buffer = StackSampleBuffer()
        buffer.add(0, stack("leaf", "root"))
        buffer.add(10, stack("other", "root"))

        assertEquals(2, buffer.stacks().size)
        assertEquals("root is shared", 3, buffer.frames().size)
        assertEquals(listOf<Any?>(0, 1), buffer.samples().map { it["s"] })
    }

    @Test
    fun stacksAreRootFirst() {
        val buffer = StackSampleBuffer()
        buffer.add(0, stack("leaf", "mid", "root"))

        val indexes = buffer.stacks().single()
        assertEquals(listOf("root", "mid", "leaf"), indexes.map { buffer.frames()[it]["m"] })
    }

    @Test
    fun deepStack_dropsFramesNearestTheRoot() {
        val buffer = StackSampleBuffer()
        buffer.add(0, Array(200) { el("m$it") }) // m0 is the leaf, m199 the root

        val indexes = buffer.stacks().single()
        assertEquals(128, indexes.size)
        assertEquals("m127", buffer.frames()[indexes.first()]["m"])
        assertEquals("m0", buffer.frames()[indexes.last()]["m"])
    }

    @Test
    fun bufferStopsAtMaxSamples() {
        val buffer = StackSampleBuffer()
        repeat(1000) { assertTrue(buffer.add(it.toLong(), stack("leaf"))) }

        assertFalse(buffer.add(1000, stack("leaf")))
        assertEquals(1000, buffer.sampleCount)
        assertEquals(1000, buffer.samples().size)
    }

    @Test
    fun emptyStack_isIgnored() {
        val buffer = StackSampleBuffer()

        assertFalse(buffer.add(0, emptyArray()))
        assertEquals(0, buffer.sampleCount)
        assertTrue(buffer.frames().isEmpty())
        assertTrue(buffer.stacks().isEmpty())
    }

    @Test
    fun negativeLine_andNullFile_areOmitted() {
        val buffer = StackSampleBuffer()
        buffer.add(0, arrayOf(el("native", file = null, line = -2), el("known", file = "A.kt", line = 42)))

        val byMethod = buffer.frames().associateBy { it["m"] }
        assertFalse(byMethod.getValue("native").containsKey("l"))
        assertFalse(byMethod.getValue("native").containsKey("f"))
        assertEquals(42, byMethod.getValue("known")["l"])
        assertEquals("A.kt", byMethod.getValue("known")["f"])
        assertEquals("com.example.A", byMethod.getValue("known")["c"])
    }

    @Test
    fun theSameMethodOnAnotherLine_isAnotherFrame() {
        val buffer = StackSampleBuffer()
        buffer.add(0, arrayOf(el("m", line = 1)))
        buffer.add(10, arrayOf(el("m", line = 2)))

        assertEquals(2, buffer.frames().size)
        assertNull(buffer.frames().firstOrNull { it["l"] == 3 })
    }

    @Test
    fun customCaps_areHonoured() {
        val buffer = StackSampleBuffer(maxSamples = 2, maxDepth = 2)
        assertTrue(buffer.add(0, stack("a", "b", "c")))
        assertTrue(buffer.add(1, stack("a", "b", "c")))
        assertFalse(buffer.add(2, stack("a", "b", "c")))
        assertEquals(2, buffer.stacks().single().size)
    }
}
