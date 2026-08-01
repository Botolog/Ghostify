package com.ghostify.perf.android.pattern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Status of a single track row, mirroring Ghostify's `songs.status`. */
enum class TrackStatus { PENDING, QUEUED, DOWNLOADING, DOWNLOADED, FAILED, REMOVED }

data class TrackSummary(
    val spotifyId: String,
    val title: String,
    val artists: String,
    val status: TrackStatus,
)

private object TrackRowContentType

/**
 * Reusable track-list pattern (T-164: a 1000-track list with no OOM).
 *
 * Guarantees, by construction (no matter how large [tracks] is):
 *  - only `ceil(viewportHeight / rowHeight)` rows are ever composed at once
 *    ([com.ghostify.perf.LazyListMetrics.maxComposedItems]) — a constant that does
 *    NOT scale with playlist size, so a 1000-track list composes ~10 rows, not 1000;
 *  - `key = { it.spotifyId }` keeps identity stable across re-syncs;
 *  - `contentType = { TrackRowContentType }` is a single constant, so Compose can
 *    REUSE row compositions when the list mutates instead of re-inflating;
 *  - rows are fixed-height (56 dp) with no intrinsic-measure children, so
 *    composition cost is O(visible), never O(n).
 *
 * Note: Compose 1.7.x `LazyColumn` has no `beyondBoundsItemCount` parameter (that
 * arrived later); prefetch here is the default bounded window. The exact
 * composition bound is computed version-agnostically by [LazyListMetrics].
 */
@Composable
fun TrackList(
    tracks: List<TrackSummary>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    onTrackLongClick: (TrackSummary) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("TrackList"),
        state = rememberLazyListState(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = tracks,
            key = { it.spotifyId },
            contentType = { TrackRowContentType },
        ) { track ->
            TrackRow(track = track, onLongClick = { onTrackLongClick(track) })
        }
    }
}

@Composable
private fun TrackRow(track: TrackSummary, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = track.title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(
                text = track.artists,
                fontSize = 13.sp,
                color = Color(0xFF9CA3AF),
            )
        }
        StatusDot(status = track.status)
    }
}

@Composable
private fun StatusDot(status: TrackStatus) {
    val color = when (status) {
        TrackStatus.DOWNLOADED -> Color(0xFF10B756)
        TrackStatus.DOWNLOADING, TrackStatus.QUEUED -> Color(0xFF3B82F6)
        TrackStatus.FAILED -> Color(0xFFEF4444)
        else -> Color(0xFF6B7280)
    }
    Text(
        text = "●",
        color = color,
        fontSize = 16.sp,
        modifier = Modifier.wrapContentWidth(),
    )
}
