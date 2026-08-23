package xyz.botolog.ghostify.sync

import xyz.botolog.ghostify.data.model.PlaylistOrigin
import xyz.botolog.ghostify.python.PlaylistMetadata

/**
 * Fetches a playlist's current metadata + ordered track list from its origin
 * (Spotify or YouTube).
 *
 * Implemented in the app by the Python bridge (`PlaylistMetadataBridge`,
 * `ghostify_dl.fetch_playlist` / `ghostify_dl.fetch_playlist_youtube`).
 * The sync use case calls this OUTSIDE any DB transaction so a slow network
 * call never holds the SQLite writer lock.
 */
interface SpotifyPlaylistFetcher {

    /**
     * Fetches the playlist metadata and tracks from the remote origin.
     *
     * @param playlistId The playlist id stored in `playlists.spotify_id`.
     * @param origin Where the playlist originated from (Spotify or YouTube).
     * @return The fetched playlist metadata including ordered tracks.
     * @throws Exception on any failure (offline, 404, timeout). The use case
     *   converts a fetch failure into [SyncException.Network] and writes nothing.
     */
    suspend fun fetchPlaylist(playlistId: String, origin: PlaylistOrigin): PlaylistMetadata
}
