package com.ghostify.di

import com.ghostify.file.MusicStore
import com.ghostify.python.PlaylistFetchResult
import com.ghostify.python.PlaylistMetadata
import com.ghostify.python.PlaylistMetadataBridge
import com.ghostify.sync.LocalFileStore
import com.ghostify.sync.SpotifyPlaylistFetcher
import com.ghostify.sync.SyncException
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
        origin: com.ghostify.data.model.PlaylistOrigin,
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
