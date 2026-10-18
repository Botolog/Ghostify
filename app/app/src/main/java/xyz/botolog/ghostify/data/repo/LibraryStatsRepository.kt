package xyz.botolog.ghostify.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.model.LibraryStats

/**
 * Whole-library aggregates for the "Library" home-screen widget.
 *
 * Room has no single global-count query today, so the counts live as individual scalar
 * queries on [SongDao] / [PlaylistDao] and are merged here:
 *  - [observe] combines the observable counts into one [Flow] the widget coordinator
 *    collects (it re-emits whenever the `songs` or `playlists` table changes, i.e. after a
 *    download, a sync or a playlist add/delete);
 *  - [get] is the one-shot cold-start read (a widget-only process renders from Room
 *    without ever touching the player).
 *
 * @property songs DAO for the `songs` table counts.
 * @property playlists DAO for the `playlists` table count.
 */
class LibraryStatsRepository(
    private val songs: SongDao,
    private val playlists: PlaylistDao,
) {

    /**
     * Observes the library aggregates as one flow.
     *
     * Re-emits whenever the `songs` or `playlists` table changes: after a download, a
     * sync, or a playlist add/delete.
     */
    fun observe(): Flow<LibraryStats> = combine(
        songs.observeSongCount(),
        songs.observeDownloadedCount(),
        songs.observeLyricsCount(),
        playlists.observeCount(),
    ) { total, downloaded, withLyrics, playlistCount ->
        LibraryStats(
            totalSongs = total,
            downloadedSongs = downloaded,
            songsWithLyrics = withLyrics,
            playlists = playlistCount,
        )
    }

    /** One-shot read of the library aggregates (cold widget path). */
    suspend fun get(): LibraryStats = LibraryStats(
        totalSongs = songs.countSongs(),
        downloadedSongs = songs.countDownloadedSongs(),
        songsWithLyrics = songs.countSongsWithLyrics(),
        playlists = playlists.count(),
    )
}
