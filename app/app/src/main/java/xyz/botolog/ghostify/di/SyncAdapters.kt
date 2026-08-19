package xyz.botolog.ghostify.di

import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.python.PlaylistFetchResult
import xyz.botolog.ghostify.python.PlaylistMetadata
import xyz.botolog.ghostify.python.PlaylistMetadataBridge
import xyz.botolog.ghostify.sync.LocalFileStore
import xyz.botolog.ghostify.sync.SpotifyPlaylistFetcher
import xyz.botolog.ghostify.sync.SyncException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapts the Python [PlaylistMetadataBridge] to the [SpotifyPlaylistFetcher]
 * contract the re-sync diff needs. The bridge is blocking, so the fetch runs on
 * [Dispatchers.IO]; any bridge failure becomes [SyncException.Network].
 */
class SpotifyPlaylistFetcherAdapter(
    private val bridge: PlaylistMetadataBridge,
) : SpotifyPlaylistFetcher {

    override suspend fun fetchPlaylist(
        playlistId: String,
        origin: xyz.botolog.ghostify.data.model.PlaylistOrigin,
    ): PlaylistMetadata =
        withContext(Dispatchers.IO) {
            when (val result = bridge.fetchPlaylistBlocking(playlistId, origin)) {
                is PlaylistFetchResult.Success -> result.metadata
                is PlaylistFetchResult.Failure ->
                    throw SyncException.Network(playlistId, RuntimeException(result.error.message))
            }
        }
}

/**
 * Adapts [MusicStore] to the [LocalFileStore] contract used by the sync diff.
 * All operations are defensive (missing/blank paths never throw).
 */
class MusicStoreLocalFileStore(
    private val store: MusicStore,
) : LocalFileStore {

    override fun exists(filePath: String?): Boolean = store.isDownloaded(filePath)

    override fun delete(filePath: String) {
        store.deleteSongFile(filePath)
    }
}
