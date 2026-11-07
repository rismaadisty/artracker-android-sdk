package id.art.tracker.playground.labs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeSpecTest {
    @Test
    fun totalNodesOfACompleteTree() {
        assertEquals(13, TreeSpec(depth = 2, width = 3).totalNodes())
        assertEquals(1, TreeSpec(depth = 0, width = 5).totalNodes())
        assertEquals(61, TreeSpec(depth = 60, width = 1).totalNodes())
    }

    @Test
    fun deepTreeExceedsTheSdkDepthLimit() {
        assertTrue(TreeSpecs.DEEP.depth > 50)
    }

    @Test
    fun wideTreeExceedsTheSdkNodeLimit() {
        assertTrue(TreeSpecs.WIDE.totalNodes() > 2000)
    }

    @Test
    fun normalTreeStaysFarUnderBothLimits() {
        assertTrue(TreeSpecs.NORMAL.totalNodes() < 200)
        assertTrue(TreeSpecs.NORMAL.depth < 50)
    }

    @Test
    fun hugeSpecsDoNotOverflow() {
        assertEquals(Int.MAX_VALUE, TreeSpec(depth = 50, width = 50).totalNodes())
    }
}
