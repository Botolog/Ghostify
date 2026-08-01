package com.ghostify.release

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ghostify.R

/**
 * A representative Ghostify screen built only from shared M3 components and the
 * GhostifyTheme. It exists so the release theme tests (T-175, T-177) can render
 * "a screen" deterministically without depending on the UI component's own
 * screens; the UI component's per-screen tests complement it. It mirrors the
 * Library screen: top app bar, scrollable playlist list, add-playlist FAB.
 */
@Composable
fun ReferenceScreen() {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.library_title)) })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { },
                modifier = Modifier.testTag("add_playlist_fab"),
            ) {
                Text(stringResource(R.string.library_add_playlist))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            items(12) { index ->
                PlaylistRow(
                    title = "Playlist ${index + 1}",
                    subtitle = "Artist ${index + 1}",
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(title: String, subtitle: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
