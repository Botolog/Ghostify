package com.ghostify.sync

import com.ghostify.python.PlaylistMetadata

/**
 * Fetches a playlist's current metadata + ordered track list from Spotify.
 *
 * Implemented in the app by the Python bridge (`PlaylistMetadataBridge`,
 * `ghostify_dl.fetch_playlist`). The sync use case calls this OUTSIDE any DB
 * transaction so a slow network call never holds the SQLite writer lock.
 */
interface SpotifyPlaylistFetcher {
    /**
     * @param spotifyId the Spotify playlist id stored in `playlists.spotify_id`.
     * @throws Exception on any failure (offline, 404, timeout). The use case
     *   converts a fetch failure into [SyncException.Network] and writes nothing.
     */
    suspend fun fetchPlaylist(spotifyId: String): PlaylistMetadata
}
