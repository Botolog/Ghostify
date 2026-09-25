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
