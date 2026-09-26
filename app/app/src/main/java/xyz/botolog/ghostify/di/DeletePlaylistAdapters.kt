package xyz.botolog.ghostify.di

import xyz.botolog.ghostify.data.repo.PlaylistMediaFiles
import xyz.botolog.ghostify.data.repo.PlaylistPlaybackReset
import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.player.ArtworkPersistence
import xyz.botolog.ghostify.player.PlayerController
import java.io.File
import timber.log.Timber

/**
 * Adapts [MusicStore] + [ArtworkPersistence] to the [PlaylistMediaFiles] contract used when a
 * playlist is deleted.
 *
 * A song's file is addressed by *both* of its possible locations, because neither is
 * authoritative on its own:
 *  - the recorded `songs.file_path`, which is where the download worker (or a completed storage
 *    migration, which moves files to `<root>/<playlistId>/`) last wrote the file, and
 *  - the path [MusicStore] resolves *now*, from the current storage root.
 *
 * Resolving through the store rather than trusting the stored string is what makes deletion
 * correct after the storage location changed: the current root wins, and the recorded path is
 * swept up as a fallback so nothing is orphaned at the old location.
 *
 * Every operation is defensive — a missing file, an unreadable root or a missing artwork
 * directory is a no-op, never a throw.
 *
 * @property store the local music file store (rooted at the current storage location).
 * @property artwork cached cover-art store; `null` skips artwork cleanup.
 */
class MusicStorePlaylistMediaFiles(
    private val store: MusicStore,
    private val artwork: ArtworkPersistence? = null,
) : PlaylistMediaFiles {

    override fun candidatePaths(artists: String, title: String, recordedPath: String?): List<String> {
        val paths = LinkedHashSet<String>(2)
        recordedPath?.trim()?.takeIf { it.isNotEmpty() }?.let { paths.add(File(it).absolutePath) }
        val currentRootPath = runCatching { store.findOutputFile(artists, title).absolutePath }
            .getOrElse {
                Timber.e(it, "MusicStorePlaylistMediaFiles: could not resolve current path")
                null
            }
        currentRootPath?.let { paths.add(it) }
        return paths.toList()
    }

    override fun delete(path: String): Boolean {
        if (path.isBlank()) return true
        return runCatching { store.deleteSongFile(path).fileDeleted }
            .getOrElse {
                Timber.e(it, "MusicStorePlaylistMediaFiles: delete $path FAILED")
                false
            }
    }

    override fun deleteArtwork(playlistId: String, songIds: List<String>) {
        val cache = artwork ?: return
        runCatching { cache.deletePlaylistCover(playlistId) }
        songIds.forEach { songId -> runCatching { cache.delete(songId) } }
    }
}

/**
 * Adapts [PlayerController] to the [PlaylistPlaybackReset] contract used when a playlist is
 * deleted.
 *
 * @property player the app's single playback controller.
 */
class PlayerControllerPlaybackReset(
    private val player: PlayerController,
) : PlaylistPlaybackReset {

    override fun reset(playlistId: String): Boolean = player.stopPlaylist(playlistId)
}
