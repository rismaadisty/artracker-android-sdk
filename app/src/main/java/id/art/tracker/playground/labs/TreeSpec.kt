package id.art.tracker.playground.labs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A complete tree of [width]-ary nodes, [depth] levels below the root. Used to push the replay walker past its caps. */
data class TreeSpec(val depth: Int, val width: Int) {
    /** Sum of width^i for i in 0..depth, saturating at Int.MAX_VALUE. */
    fun totalNodes(): Int {
        var level = 1L
        var sum = 0L
        for (i in 0..depth) {
            sum += level
            if (sum >= Int.MAX_VALUE) return Int.MAX_VALUE
            level *= width
        }
        return sum.toInt()
    }
}

object TreeSpecs {
    /** Deeper than the SDK's depth limit (50). */
    val DEEP = TreeSpec(depth = 60, width = 1)

    /** More nodes than the SDK's per-frame limit (2000): 1 + 14 + 196 + 2744 = 2955. */
    val WIDE = TreeSpec(depth = 3, width = 14)

    /** Far under both limits. */
    val NORMAL = TreeSpec(depth = 2, width = 3)
}

/** One `Text` per node, nested in `Column`s so the whole tree stays on screen. */
@Composable
fun GeneratedTree(spec: TreeSpec) {
    Node(spec.depth, spec.width, "0")
}

@Composable
private fun Node(remaining: Int, width: Int, label: String) {
    Column(Modifier.padding(start = 1.dp)) {
        Text(label, fontSize = 8.sp)
        if (remaining > 0) repeat(width) { i -> Node(remaining - 1, width, "$label.$i") }
    }
}
