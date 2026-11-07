package id.art.tracker.playground.labs

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** A pad that reacts to tap, double tap, long press and drag, and shows the last gesture it saw. */
@Composable
fun GestureArena(modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf("belum ada gestur") }
    var count by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { last = "tap"; count++ },
                    onDoubleTap = { last = "double tap"; count++ },
                    onLongPress = { last = "long press"; count++ },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { last = "drag"; count++ }) { _, _ -> }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("$last ($count)")
    }
}
