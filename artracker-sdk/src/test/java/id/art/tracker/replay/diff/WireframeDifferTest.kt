package id.art.tracker.replay.diff

import id.art.tracker.replay.model.Wireframe
import id.art.tracker.replay.model.WireframeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireframeDifferTest {
    private fun box(id: Int, x: Int = 0, y: Int = 0, w: Int = 100, h: Int = 50, vararg kids: Wireframe) =
        Wireframe(id, x, y, w, h, WireframeType.CONTAINER, children = kids.toList())

    private fun label(id: Int, text: String, x: Int = 0, y: Int = 0) =
        Wireframe(id, x, y, 80, 20, WireframeType.TEXT, text = text)

    private fun root(vararg kids: Wireframe) = box(1, 0, 0, 360, 640, *kids)

    @Test
    fun diff_identical_isEmpty() {
        val tree = root(box(2, 0, 0, 100, 50, label(3, "hi")), label(4, "yo"))
        val diff = WireframeDiffer.diff(tree, tree.copy())
        assertTrue(diff.isEmpty)
        assertEquals(WireframeDiff(emptyList(), emptyList(), emptyList(), emptyList()), diff)
    }

    @Test
    fun diff_textChange() {
        val diff = WireframeDiffer.diff(root(label(2, "old")), root(label(2, "new")))
        assertEquals(listOf(2 to "new"), diff.texts)
        assertTrue(diff.adds.isEmpty())
        assertTrue(diff.removes.isEmpty())
        assertTrue(diff.updates.isEmpty())
        assertFalse(diff.isEmpty)
    }

    @Test
    fun diff_addAndRemove() {
        val diff = WireframeDiffer.diff(root(box(2), box(3)), root(box(2), box(4)))
        assertEquals(listOf(1 to 3), diff.removes)
        assertEquals(listOf(1), diff.adds.map { it.first })
        assertEquals(listOf(4), diff.adds.map { it.second.id })
        assertTrue(diff.updates.isEmpty())
        assertTrue(diff.texts.isEmpty())
    }

    @Test
    fun diff_boundsChangeIsUpdate() {
        val diff = WireframeDiffer.diff(root(box(2, 0, 0, 100, 50)), root(box(2, 5, 0, 100, 60)))
        assertEquals(listOf(2), diff.updates.map { it.id })
        assertEquals(60, diff.updates.single().height)
        assertTrue(diff.adds.isEmpty())
        assertTrue(diff.removes.isEmpty())
    }

    @Test
    fun diff_styleAlphaMaskedChangesAreUpdates() {
        val base = root(box(2))
        for (changed in listOf(
            box(2).copy(background = 0xFF112233.toInt()),
            box(2).copy(alpha = 0.5f),
            box(2).copy(masked = true),
            box(2).copy(textColor = 1),
        )) {
            assertEquals(listOf(2), WireframeDiffer.diff(base, root(changed)).updates.map { it.id })
        }
    }

    @Test
    fun diff_movedNodeIsRemoveAndAdd() {
        val prev = root(box(2, 0, 0, 100, 50, box(5)), box(3))
        val next = root(box(2), box(3, 0, 0, 100, 50, box(5)))
        val diff = WireframeDiffer.diff(prev, next)
        assertEquals(listOf(2 to 5), diff.removes)
        assertEquals(listOf(3 to 5), diff.adds.map { it.first to it.second.id })
        assertTrue(diff.updates.isEmpty())
    }

    @Test
    fun diff_removedSubtreeRemovedOnce() {
        val prev = root(box(2, 0, 0, 100, 50, box(3, 0, 0, 10, 10, box(4)), label(5, "x")))
        val next = root()
        val diff = WireframeDiffer.diff(prev, next)
        assertEquals(listOf(1 to 2), diff.removes)
        assertTrue(diff.adds.isEmpty())
    }

    @Test
    fun diff_addedSubtreeIsOneAdd() {
        val added = box(2, 0, 0, 100, 50, box(3, 0, 0, 10, 10, box(4)), label(5, "x"))
        val diff = WireframeDiffer.diff(root(), root(added))
        assertEquals(1, diff.adds.size)
        assertEquals(1 to added, diff.adds.single())
        assertTrue(diff.removes.isEmpty())
        assertTrue(diff.texts.isEmpty())
        assertTrue(diff.updates.isEmpty())
    }

    @Test
    fun diff_addCarriesNextStableSibling() {
        val diff = WireframeDiffer.diff(root(box(2), box(4)), root(box(2), box(3), box(4)))
        assertEquals(listOf(3), diff.adds.map { it.second.id })
        assertEquals(listOf<Int?>(4), diff.addBefore)
        val appended = WireframeDiffer.diff(root(box(2)), root(box(2), box(3)))
        assertEquals(listOf<Int?>(null), appended.addBefore)
    }

    @Test
    fun diff_reorderedChildrenMoveMinimalSet() {
        val diff = WireframeDiffer.diff(root(box(2), box(3), box(4)), root(box(4), box(2), box(3)))
        assertEquals(listOf(1 to 4), diff.removes)
        assertEquals(listOf(1 to 4), diff.adds.map { it.first to it.second.id })
        assertEquals(listOf<Int?>(2), diff.addBefore)
    }

    @Test
    fun diff_childFollowingParentShiftIsNotAnUpdate() {
        // Child screen coords shift together with the parent, so its parent-relative geometry is unchanged.
        val prev = root(box(2, 10, 10, 100, 50, box(3, 12, 14, 20, 20)))
        val next = root(box(2, 30, 40, 100, 50, box(3, 32, 44, 20, 20)))
        assertEquals(listOf(2), WireframeDiffer.diff(prev, next).updates.map { it.id })
    }

    @Test
    fun diff_childStaysPutWhenParentMovesIsUpdate() {
        val prev = root(box(2, 10, 10, 100, 50, box(3, 12, 14, 20, 20)))
        val next = root(box(2, 30, 40, 100, 50, box(3, 12, 14, 20, 20)))
        assertEquals(listOf(2, 3), WireframeDiffer.diff(prev, next).updates.map { it.id })
    }

    @Test
    fun diff_typeChangeReplacesNode() {
        val prev = root(Wireframe(2, 0, 0, 10, 10, WireframeType.TEXT, text = "a"))
        val next = root(Wireframe(2, 0, 0, 10, 10, WireframeType.IMAGE))
        val diff = WireframeDiffer.diff(prev, next)
        assertEquals(listOf(1 to 2), diff.removes)
        assertEquals(listOf(2), diff.adds.map { it.second.id })
    }

    @Test
    fun diff_textAppearingReplacesNode() {
        val prev = root(Wireframe(2, 0, 0, 10, 10, WireframeType.TEXT, text = null))
        val next = root(Wireframe(2, 0, 0, 10, 10, WireframeType.TEXT, text = "now"))
        val diff = WireframeDiffer.diff(prev, next)
        assertEquals(listOf(1 to 2), diff.removes)
        assertTrue(diff.texts.isEmpty())
    }

    @Test
    fun diff_nodesInsideReAddedSubtreeEmitNoUpdates() {
        val prev = root(box(2, 0, 0, 10, 10, label(4, "old")), box(3, 0, 0, 10, 10))
        val next = root(box(3, 0, 0, 10, 10, box(2, 0, 0, 10, 10, label(4, "new"))))
        val diff = WireframeDiffer.diff(prev, next)
        assertEquals(listOf(1 to 2), diff.removes)
        assertEquals(1, diff.adds.size)
        assertTrue(diff.texts.isEmpty())
        assertTrue(diff.updates.isEmpty())
    }

    @Test
    fun diff_rootReplacedUsesVirtualParent() {
        val diff = WireframeDiffer.diff(box(1), box(9))
        assertEquals(listOf(WireframeDiffer.ROOT_PARENT_ID to 1), diff.removes)
        assertEquals(listOf(WireframeDiffer.ROOT_PARENT_ID to 9), diff.adds.map { it.first to it.second.id })
    }

    @Test
    fun diff_isDeterministic() {
        val prev = root(box(2), label(3, "a"), box(4, 0, 0, 5, 5), box(5))
        val next = root(box(5), label(3, "b"), box(4, 1, 0, 5, 5), box(6), box(2))
        assertEquals(WireframeDiffer.diff(prev, next), WireframeDiffer.diff(prev, next))
    }
}
