package id.art.tracker.sample.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val feedItems = List(500) { "Feed item #${it + 1}" }

/** 500 rows: scroll to exercise replay diffs. */
@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(feedItems) { Text(it, Modifier.fillMaxWidth().padding(16.dp)) }
        }
    }
}
