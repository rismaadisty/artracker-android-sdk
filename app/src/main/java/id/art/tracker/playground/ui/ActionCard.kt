package id.art.tracker.playground.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One runnable action. [onRun]'s returned text (if any) becomes the feedback snackbar. A [destructive] card asks for
 * confirmation first; a card with a [disabledReason] never runs and shows the reason instead.
 */
@Composable
fun ActionCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    confirmText: String? = null,
    disabledReason: String? = null,
    onRun: () -> String?,
) {
    var presses by remember { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    val feedback = LocalFeedback.current

    fun run() {
        presses++
        onRun()?.let(feedback::show)
    }

    Card(
        modifier.fillMaxWidth().clickable(enabled = disabledReason == null) {
            if (destructive) confirming = true else run()
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (disabledReason != null) {
                Text(disabledReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (presses > 0) Text("${presses}×", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = title,
            text = confirmText ?: description,
            onConfirm = {
                confirming = false
                run()
            },
            onDismiss = { confirming = false },
        )
    }
}
