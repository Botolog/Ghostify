package xyz.botolog.ghostify.ui.playlist

import xyz.botolog.ghostify.ui.model.SongStatus
import xyz.botolog.ghostify.ui.model.TrackUi
import java.text.Collator
import java.util.Locale

enum class PlaylistSortOption {
    PLAYLIST_ORDER,
    TITLE,
    ARTIST,
    ALBUM,
    DURATION,
    DATE_ADDED,
    DOWNLOADED_STATUS,
}

data class PlaylistSortSpec(
    val option: PlaylistSortOption = PlaylistSortOption.PLAYLIST_ORDER,
    val descending: Boolean = false,
)

fun sortPlaylistTracks(
    tracks: List<TrackUi>,
    specification: PlaylistSortSpec = PlaylistSortSpec(),
): List<TrackUi> = tracks.sortedWith(playlistTrackComparator(specification))

/**
 * Resolves a [PlaylistSortSpec] into the resulting playlist order, as song ids.
 *
 * This is the order the sort persists into the database (`songs.position`); the
 * id tie-breaker inside [playlistTrackComparator] keeps it stable, so
 * re-running the same sort is a no-op.
 *
 * @param tracks the playlist's current track set (in any order).
 * @param specification the sort to apply.
 * @return the track ids in the order they should be stored in.
 */
fun sortedTrackIds(
    tracks: List<TrackUi>,
    specification: PlaylistSortSpec = PlaylistSortSpec(),
): List<String> = tracks.sortedWith(playlistTrackComparator(specification)).map { it.id }

/**
 * `true` when [this] asks for the order already stored in the database, i.e.
 * "playlist order" ascending. Persisting it would rewrite every position with the
 * value it already has, so callers skip the write entirely.
 */
fun PlaylistSortSpec.isStoredOrder(): Boolean =
    option == PlaylistSortOption.PLAYLIST_ORDER && !descending

/**
 * Rewrites [this] list of tracks into the order [order] describes, with
 * `position` renumbered to match its index in that order.
 *
 * Tracks missing from [order] are kept (appended after the ordered ones) so a
 * stale order can never hide a track.
 */
fun List<TrackUi>.orderedBy(order: List<String>): List<TrackUi> {
    if (isEmpty()) return this
    val rank = HashMap<String, Int>(order.size * 2)
    order.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
    return sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        .mapIndexed { index, track ->
            if (track.position == index) track else track.copy(position = index)
        }
}

fun playlistTrackComparator(specification: PlaylistSortSpec): Comparator<TrackUi> {
    val collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.PRIMARY
    }
    val primaryComparator = when (specification.option) {
        PlaylistSortOption.PLAYLIST_ORDER -> Comparator<TrackUi> { first, second ->
            first.position.compareTo(second.position)
        }
        PlaylistSortOption.TITLE -> Comparator<TrackUi> { first, second ->
            collator.compare(first.title, second.title)
        }
        PlaylistSortOption.ARTIST -> Comparator<TrackUi> { first, second ->
            collator.compare(first.artists, second.artists)
        }
        PlaylistSortOption.ALBUM -> Comparator<TrackUi> { first, second ->
            collator.compare(first.album, second.album)
        }
        PlaylistSortOption.DURATION -> Comparator<TrackUi> { first, second ->
            first.durationMs.compareTo(second.durationMs)
        }
        PlaylistSortOption.DATE_ADDED -> Comparator<TrackUi> { first, second ->
            first.addedAtValue().compareTo(second.addedAtValue())
        }
        PlaylistSortOption.DOWNLOADED_STATUS -> Comparator<TrackUi> { first, second ->
            val firstDownloaded = first.status == SongStatus.DOWNLOADED
            val secondDownloaded = second.status == SongStatus.DOWNLOADED
            when {
                firstDownloaded == secondDownloaded -> 0
                firstDownloaded -> 1
                else -> -1
            }
        }
    }
    return Comparator { first, second ->
        val primaryResult = primaryComparator.compare(first, second)
        val directedResult = if (specification.descending) -primaryResult else primaryResult
        if (directedResult != 0) directedResult else first.id.compareTo(second.id)
    }
}

private fun TrackUi.addedAtValue(): Long = addedAt ?: Long.MIN_VALUE
