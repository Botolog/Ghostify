package com.ghostify.perf.android.pattern

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal view-model for the library screen, used only by the perf benchmarks.
 * The real Ghostify `Playlist` entity is richer; this carries the columns the
 * jank budget (T-163) actually reads while composing a row.
 */
data class PlaylistSummary(
    val id: String,
    val name: String,
    val owner: String,
    val trackCount: Int,
)

/**
 * Reusable library-list pattern (T-163: render 100 playlists without jank).
 *
 * Why this stays under 16 ms/frame:
 *  - `LazyColumn` composes ONLY the visible window + a small prefetch, never all
 *    [playlists] (see [com.ghostify.perf.LazyListMetrics.maxComposedItems]).
 *  - `key = { it.id }` gives each row a stable identity so a progress/status
 *    update recomposes only the changed row, not its neighbours.
 *  - Rows are fixed-height (exactly 80 dp) — no intrinsic-measure pass, which is
 *    the single most common source of dropped frames in lazy lists.
 *  - `.testTag("PlaylistList")` exposes the list to the Compose test API so the
 *    benchmark can scroll it deterministically.
 */
@Composable
fun PlaylistList(
    playlists: List<PlaylistSummary>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    onPlaylistClick: (PlaylistSummary) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("PlaylistList"),
        state = rememberLazyListState(),
        contentPadding = contentPadding,
    ) {
        items(
            items = playlists,
            key = { it.id },
        ) { playlist ->
            PlaylistSumRow(playlist = playlist, onClick = { onPlaylistClick(playlist) })
        }
    }
}

@Composable
private fun PlaylistSumRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = playlist.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${playlist.owner} · ${playlist.trackCount} tracks",
            fontSize = 13.sp,
        )
    }
}
