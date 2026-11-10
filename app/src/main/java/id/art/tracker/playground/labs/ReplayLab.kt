package id.art.tracker.playground.labs

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import id.art.tracker.R
import id.art.tracker.playground.local.LocalServersProvider
import id.art.tracker.playground.settings.SharedPrefsSettingsStore
import id.art.tracker.playground.status.PlaygroundControls
import id.art.tracker.playground.ui.ActionCard

private val feedItems = List(500) { "Item #${it + 1} · ${"abcdefghij".repeat(1 + it % 4)}" }

/** Scrolling, paging, gestures, animation, overlays and oversized trees: everything the replay walker must survive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayLab(modifier: Modifier = Modifier) {
    val controls = PlaygroundControls.get(LocalContext.current)
    var replayOn by remember { mutableStateOf(controls.replayOn) }
    var animating by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var popup by remember { mutableStateOf(false) }
    var tree by remember { mutableStateOf<TreeSpec?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.lab_replay_controls), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.lab_replay_intent, if (replayOn) "ON" else "OFF"), style = MaterialTheme.typography.bodyMedium)
        ActionCard(stringResource(R.string.lab_replay_start), stringResource(R.string.lab_replay_start_desc)) {
            controls.setReplay(true)
            replayOn = controls.replayOn
            "replay dimulai"
        }
        ActionCard(stringResource(R.string.lab_replay_stop), stringResource(R.string.lab_replay_stop_desc)) {
            controls.setReplay(false)
            replayOn = controls.replayOn
            "replay dihentikan"
        }

        ScreenshotSection()

        Text(stringResource(R.string.lab_replay_list), style = MaterialTheme.typography.titleSmall)
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(feedItems) { Text(it, Modifier.fillMaxWidth().padding(8.dp)) }
            }
        }

        Text(stringResource(R.string.lab_replay_pager), style = MaterialTheme.typography.titleSmall)
        val pager = rememberPagerState(pageCount = { 5 })
        HorizontalPager(pager, Modifier.fillMaxWidth().height(100.dp)) { page ->
            Card(Modifier.fillMaxSize().padding(horizontal = 8.dp)) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Halaman ${page + 1}") } }
        }

        Text(stringResource(R.string.lab_replay_gestures), style = MaterialTheme.typography.titleSmall)
        GestureArena()

        Text(stringResource(R.string.lab_replay_animation), style = MaterialTheme.typography.titleSmall)
        Switch(checked = animating, onCheckedChange = { animating = it })
        if (animating) MovingBox()

        Text(stringResource(R.string.lab_replay_overlays), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.lab_replay_overlays_note), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { dialog = true }) { Text(stringResource(R.string.lab_replay_dialog)) }
        Button(onClick = { sheet = true }) { Text(stringResource(R.string.lab_replay_sheet)) }
        Box {
            Button(onClick = { menu = true }) { Text(stringResource(R.string.lab_replay_menu)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Item menu") }, onClick = { menu = false })
            }
        }
        Button(onClick = { popup = !popup }) { Text(stringResource(R.string.lab_replay_popup)) }
        if (popup) Popup { Card { Text("Isi popup", Modifier.padding(12.dp)) } }

        Text(stringResource(R.string.lab_replay_trees), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.lab_replay_trees_note), style = MaterialTheme.typography.bodySmall)
        ActionCard(stringResource(R.string.lab_replay_tree_normal), stringResource(R.string.lab_replay_tree_desc, TreeSpecs.NORMAL.totalNodes())) {
            tree = TreeSpecs.NORMAL
            "pohon normal ditampilkan"
        }
        ActionCard(stringResource(R.string.lab_replay_tree_wide), stringResource(R.string.lab_replay_tree_desc, TreeSpecs.WIDE.totalNodes())) {
            tree = TreeSpecs.WIDE
            "pohon lebar ditampilkan"
        }
        ActionCard(stringResource(R.string.lab_replay_tree_deep), stringResource(R.string.lab_replay_tree_desc, TreeSpecs.DEEP.totalNodes())) {
            tree = TreeSpecs.DEEP
            "pohon dalam ditampilkan"
        }
        ActionCard(stringResource(R.string.lab_replay_tree_hide), stringResource(R.string.lab_replay_tree_hide_desc)) {
            tree = null
            "pohon disembunyikan"
        }
        tree?.let { GeneratedTree(it) }

        Text(stringResource(R.string.lab_replay_rotate), style = MaterialTheme.typography.bodySmall)
    }

    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Dialog") },
            text = { Text("Dialog berada di window lain.") },
            confirmButton = { TextButton(onClick = { dialog = false }) { Text("Tutup") } },
        )
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Text("Bottom sheet", Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun MovingBox() {
    val transition = rememberInfiniteTransition(label = "move")
    val x by transition.animateFloat(
        initialValue = 0f, targetValue = 200f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Reverse), label = "x",
    )
    Box(Modifier.fillMaxWidth().height(40.dp)) {
        Box(Modifier.offset(x = x.dp).size(32.dp).background(MaterialTheme.colorScheme.primary))
    }
}

/** Screenshot replay: the saved quality, whether the local sink accepts screenshots, what it received, and an export. */
@Composable
private fun ScreenshotSection() {
    val context = LocalContext.current
    val servers = LocalServersProvider.instance
    val saved = remember { SharedPrefsSettingsStore(context).load() }
    var allowed by remember { mutableStateOf(servers.screenshotAllowed) }
    Text(stringResource(R.string.lab_replay_screenshot), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.lab_replay_screenshot_mode, saved.replayQuality.name),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (servers.sinkAvailable) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.lab_replay_screenshot_server))
            Switch(
                checked = allowed,
                onCheckedChange = {
                    servers.screenshotAllowed = it
                    allowed = servers.screenshotAllowed
                },
                modifier = Modifier.testTag("lab_screenshot_allowed"),
            )
        }
        Text(stringResource(R.string.lab_replay_screenshot_server_note), style = MaterialTheme.typography.bodySmall)
    }
    ActionCard(stringResource(R.string.lab_replay_screenshot_summary), stringResource(R.string.lab_replay_screenshot_summary_desc)) {
        servers.screenshotSummary() ?: "tidak tersedia di build ini"
    }
    ActionCard(stringResource(R.string.lab_replay_screenshot_export), stringResource(R.string.lab_replay_screenshot_export_desc)) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        servers.exportScreenshots(dir)?.let { "${it.size} frame ditulis ke ${dir.absolutePath}" } ?: "tidak ada yang bisa diekspor"
    }
}
