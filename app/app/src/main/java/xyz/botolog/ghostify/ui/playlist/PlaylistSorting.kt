package xyz.botolog.ghostify.ui.playlist

import xyz.botolog.ghostify.ui.model.SongStatus
import xyz.botolog.ghostify.ui.model.TrackUi
import java.text.Collator
import java.util.Locale

/**
 * The orderings offered by the playlist sort sheet.
 *
 * [storageValue] is what is written to (and read back from) the saved sort
 * state, so it is a stable snake_case token rather than the enum's ordinal: the
 * declaration order can change without invalidating a saved draft.
 *
 * @property storageValue stable token identifying this option across saves.
 */
enum class PlaylistSortOption(val storageValue: String) {
    PLAYLIST_ORDER("playlist_order"),
    TITLE("title"),
    ARTIST("artist"),
    ALBUM("album"),
    ARTIST_ALBUM_TITLE("artist_album_title"),
    DURATION("duration"),
    DATE_ADDED("date_added"),
    DOWNLOADED_STATUS("downloaded_status"),
    ;

    companion object {

        /**
         * Resolves a [storageValue] back to its option.
         *
         * Unknown or missing values resolve to `null` so callers can fall back
         * to the sort that is already committed instead of silently resetting it.
         *
         * @param raw the stored token, or `null` when unset.
         * @return the matching option, or `null` when it is missing or unknown.
         */
        fun fromStorageValue(raw: String?): PlaylistSortOption? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.storageValue == normalized }
        }
    }
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

/**
 * Builds the comparator for a [PlaylistSortSpec].
 *
 * Text keys (title / artist / album, including the artist → album → song
 * combination) are compared with a locale-aware [Collator] at
 * [Collator.PRIMARY] strength, so case and accents do not affect the order.
 * Missing metadata arrives as `null` / blank text and compares as empty text,
 * exactly like the single-field options do, which keeps it grouped before
 * titled entries; equal keys fall through to the next key and finally to the
 * track id, so the result is deterministic and re-sorting is a no-op.
 */
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
        PlaylistSortOption.ARTIST_ALBUM_TITLE -> Comparator<TrackUi> { first, second ->
            compareTextKeys(collator, first, second, ARTIST_ALBUM_TITLE_KEYS)
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

/**
 * The text keys of [PlaylistSortOption.ARTIST_ALBUM_TITLE], in priority order:
 * artist, then album within that artist, then title within that album.
 */
private val ARTIST_ALBUM_TITLE_KEYS: Array<(TrackUi) -> String?> = arrayOf(
    { it.artists },
    { it.album },
    { it.title },
)

/**
 * Compares two tracks on each selector in turn, returning the first key that
 * actually differs and `0` when every key is equal.
 */
private fun compareTextKeys(
    collator: Collator,
    first: TrackUi,
    second: TrackUi,
    selectors: Array<out (TrackUi) -> String?>,
): Int {
    for (selector in selectors) {
        val result = collator.compare(selector(first), selector(second))
        if (result != 0) return result
    }
    return 0
}

