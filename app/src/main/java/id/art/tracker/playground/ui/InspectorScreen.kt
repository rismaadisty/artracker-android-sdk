package id.art.tracker.playground.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.art.tracker.ARTracker
import id.art.tracker.R
import id.art.tracker.playground.inspector.EventRing
import id.art.tracker.playground.inspector.InspectedItem
import id.art.tracker.playground.inspector.Inspector
import id.art.tracker.playground.inspector.InspectorFilter
import id.art.tracker.playground.inspector.ItemSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val TYPES = listOf("event", "log", "session")

/**
 * What the SDK reported. [mode] says which kind of data [ring] holds: the sent (post-redaction) payloads from the sink,
 * or value-free metadata. There is deliberately no control that reveals raw values. While visible it flushes every
 * [autoRefreshMs] (0 disables), because items appear only after the SDK sent them.
 */
@Composable
fun InspectorScreen(
    ring: EventRing = Inspector.ring,
    mode: ItemSource,
    onRefresh: () -> Unit = { ARTracker.flush() },
    autoRefreshMs: Long = 2_000,
) {
    val items by ring.items.collectAsState()
    val total by ring.totalSeen.collectAsState()
    var query by remember { mutableStateOf("") }
    var types by remember { mutableStateOf(emptySet<String>()) }
    val filter = InspectorFilter(types, query)

    if (autoRefreshMs > 0) {
        LaunchedEffect(Unit) {
            while (isActive) {
                onRefresh()
                delay(autoRefreshMs)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (mode == ItemSource.SENT_PAYLOAD) R.string.inspector_banner_sent else R.string.inspector_banner_metadata),
            style = MaterialTheme.typography.bodySmall,
            color = LocalStatusColors.current.retry,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) { Text(stringResource(R.string.inspector_refresh)) }
            OutlinedButton(onClick = { ring.clear() }) { Text(stringResource(R.string.inspector_clear)) }
            Text(stringResource(R.string.inspector_total, total), style = MaterialTheme.typography.labelMedium)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.inspector_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("search"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TYPES.forEach { t ->
                FilterChip(
                    selected = t in types,
                    onClick = { types = if (t in types) types - t else types + t },
                    label = { Text(t) },
                )
            }
        }
        val shown = items.filter(filter::matches)
        if (shown.isEmpty()) Text(stringResource(R.string.inspector_empty), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(shown) { _, item -> ItemRow(item) }
        }
        Text(stringResource(R.string.inspector_footnote), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ItemRow(item: InspectedItem) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(10.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall)
            val level = item.level?.let { " · $it" }.orEmpty()
            Text("${item.type}$level · ${formatTime(item.timestampMs)}", style = MaterialTheme.typography.labelSmall)
            if (item.source == ItemSource.METADATA_ONLY) {
                Text(stringResource(R.string.inspector_value_hidden), style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                item.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                item.attributes.forEach { (k, v) -> Text("$k = $v", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
