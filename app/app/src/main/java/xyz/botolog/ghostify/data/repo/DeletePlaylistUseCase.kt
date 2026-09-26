package xyz.botolog.ghostify.data.repo

import xyz.botolog.ghostify.data.db.TransactionRunner
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import java.io.File
import timber.log.Timber

/**
 * Resolves and removes the on-disk media of a playlist that is being deleted.
 *
 * Declared in the data layer (and implemented in `di` over `MusicStore` +
 * `ArtworkPersistence`) for the same reason [xyz.botolog.ghostify.sync.LocalFileStore]
 * is: the use case must be unit-testable on the JVM with no filesystem layout of its own.
 */
interface PlaylistMediaFiles {

    /**
     * Every absolute path a song's media file may currently live at.
     *
     * A recorded `file_path` can be stale — the storage root can be changed after a download,
     * and a migrated song lives in a per-playlist subfolder rather than at the store root — so
     * the caller must consider all candidates, not just the recorded one.
     *
     * @param artists the song's `artists` column, as stored.
     * @param title the song's `title` column, as stored.
     * @param recordedPath the song's stored `file_path`, or `null`/blank when it has none.
     * @return distinct absolute paths, most authoritative first; never contains blanks.
     */
    fun candidatePaths(artists: String, title: String, recordedPath: String?): List<String>

    /**
     * Removes the media file at [path] together with its spotdl sidecar.
     *
     * Must be safe for a missing or already-deleted file: a path that is not there counts as
     * removed and must not throw.
     *
     * @param path absolute path to the media file.
     * @return `true` when no media file is left at [path] after the call.
     */
    fun delete(path: String): Boolean

    /**
     * Removes the cached cover art belonging to the deleted playlist and its songs.
     *
     * Must never throw; a missing cache directory is not an error.
     *
     * @param playlistId the deleted playlist's row id.
     * @param songIds the ids of the song rows that were deleted with it.
     */
    fun deleteArtwork(playlistId: String, songIds: List<String>)
}

/**
 * Tears down playback that belongs to a playlist which no longer exists.
 *
 * The implementation must be a no-op for a playlist that is not the loaded one, so deleting
 * one playlist can never interrupt another.
 */
fun interface PlaylistPlaybackReset {

    /**
     * Stops playback and clears player state owned by [playlistId].
     *
     * @param playlistId the deleted playlist's row id.
     * @return `true` when that playlist was the loaded one and the player was reset; `false`
     *   when a different playlist (or nothing) was loaded and nothing was touched.
     */
    fun reset(playlistId: String): Boolean
}

/**
 * What a single playlist deletion actually removed.
 *
 * @property playlistId the playlist that was deleted.
 * @property playlistExisted `false` when the row was already gone (an idempotent re-delete).
 * @property songsDeleted number of `songs` rows removed with the playlist.
 * @property filesRemoved number of media paths confirmed gone afterwards.
 * @property filesRetained number of media paths left in place because another song row still
 *   records them.
 * @property playbackStopped `true` when the deleted playlist was the loaded one and the player
 *   was therefore reset.
 */
data class PlaylistDeletion(
    val playlistId: String,
    val playlistExisted: Boolean = false,
    val songsDeleted: Int = 0,
    val filesRemoved: Int = 0,
    val filesRetained: Int = 0,
    val playbackStopped: Boolean = false,
)

/**
 * Deletes a playlist with everything that belongs to it, and nothing else.
 *
 * Order matters, and is the whole point of this use case:
 *  1. **Playback first.** A still-playing item would keep a deleted file open in the
 *     notification and keep the mini player populated, so the queue is torn down before any
 *     file is unlinked. Player state is cleared by playlist id, so a different playlist that
 *     happens to be playing is left alone.
 *  2. **Rows in one transaction.** The playlist row and its `songs` rows go together, using
 *     the same explicit songs-then-playlist order as
 *     [PlaylistRepository.savePlaylistWithSongs], so a failure cannot leave a playlist whose
 *     tracks are half gone.
 *  3. **Files after commit, ownership-checked.** The surviving `songs` rows are read *after*
 *     the delete, so what they record is exactly what other playlists still own. A track can
 *     legitimately appear in two playlists (the unique index is `(playlist_id, spotify_id)`,
 *     not global, and both rows point at the same file), so a file still recorded by another
 *     row is never deleted. Everything else is removed, addressed by both its recorded path
 *     and its path under the *current* storage root.
 *  4. **Idempotent.** Re-deleting an already-deleted playlist removes nothing, still resets the
 *     player if that playlist is the loaded one, and never throws.
 *
 * File deletion is intentionally best-effort (see [PlaylistMediaFiles]): rows are the source of
 * truth, and a file that cannot be removed is reclaimed later by `MusicStore.clearOrphans`.
 *
 * @property playlistDao the `playlists` table.
 * @property songDao the `songs` table.
 * @property transactions provides the transactional write boundary.
 * @property files resolves and removes the playlist's media and artwork.
 * @property playback stops playback that belongs to the playlist.
 */
class DeletePlaylistUseCase(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val transactions: TransactionRunner,
    private val files: PlaylistMediaFiles,
    private val playback: PlaylistPlaybackReset,
) {

    /**
     * Deletes [playlistId] together with its song rows, media files and artwork, and resets
     * the player when that playlist is the one loaded.
     *
     * @param playlistId the playlist row id to delete.
     * @return a [PlaylistDeletion] describing what was removed.
     */
    suspend fun delete(playlistId: String): PlaylistDeletion {
        Timber.i("DeletePlaylistUseCase.delete: START $playlistId")
        val removed = removeRows(playlistId)
        val (existed, songs) = removed
        val playbackStopped = runCatching { playback.reset(playlistId) }.getOrElse { failure ->
            Timber.e(failure, "DeletePlaylistUseCase.delete: playback reset FAILED")
            false
        }
        val retained = retainedFilePaths()
        val handled = HashSet<String>()
        var filesRemoved = 0
        var filesRetained = 0
        for (song in songs) {
            for (path in candidatePathsOf(song.artists, song.title, song.filePath)) {
                if (!handled.add(path)) continue
                if (path in retained) {
                    filesRetained++
                    Timber.d("DeletePlaylistUseCase.delete: keeping $path, still owned by another song")
                    continue
                }
                val removed = runCatching { files.delete(path) }.getOrElse { failure ->
                    Timber.e(failure, "DeletePlaylistUseCase.delete: delete $path FAILED")
                    false
                }
                if (removed) filesRemoved++
            }
        }
        runCatching { files.deleteArtwork(playlistId, songs.map { it.id }) }
            .onFailure { Timber.e(it, "DeletePlaylistUseCase.delete: artwork cleanup FAILED") }
        val result = PlaylistDeletion(
            playlistId = playlistId,
            playlistExisted = existed,
            songsDeleted = songs.size,
            filesRemoved = filesRemoved,
            filesRetained = filesRetained,
            playbackStopped = playbackStopped,
        )
        Timber.i("DeletePlaylistUseCase.delete: returning $result")
        return result
    }

    /**
     * Removes the playlist row and its songs atomically, returning whether the playlist existed
     * and the song rows that were removed (needed after the commit, for file cleanup).
     */
    private suspend fun removeRows(playlistId: String): Pair<Boolean, List<xyz.botolog.ghostify.data.db.entity.SongEntity>> =
        transactions.withinTransaction {
            val existed = playlistDao.getById(playlistId) != null
            val songs = songDao.getSongsForPlaylist(playlistId)
            songDao.deleteSongsForPlaylist(playlistId)
            playlistDao.deleteById(playlistId)
            existed to songs
        }

    /**
     * The media files still owned by a surviving song row, as absolute paths.
     *
     * Read after the delete, so a path belonging to another playlist is protected while a
     * path belonging only to the deleted playlist is free to go.
     */
    private suspend fun retainedFilePaths(): Set<String> =
        runCatching { songDao.allFilePaths() }
            .getOrElse {
                Timber.e(it, "DeletePlaylistUseCase.delete: could not read retained file paths")
                emptyList()
            }
            .mapTo(HashSet()) { File(it.trim()).absolutePath }

    /** Normalised, blank-free candidate paths for one song row. */
    private fun candidatePathsOf(artists: String, title: String, recordedPath: String?): List<String> =
        runCatching { files.candidatePaths(artists, title, recordedPath) }
            .getOrElse {
                Timber.e(it, "DeletePlaylistUseCase.delete: path resolution FAILED")
                emptyList()
            }
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it).absolutePath }
            .distinct()
            .toList()
}
