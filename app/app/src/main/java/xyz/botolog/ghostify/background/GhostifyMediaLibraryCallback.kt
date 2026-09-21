package xyz.botolog.ghostify.background

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.guava.future
import timber.log.Timber
import xyz.botolog.ghostify.GhostifyApplication
import xyz.botolog.ghostify.data.model.SongStatus
import xyz.botolog.ghostify.player.MediaItemMapper
import xyz.botolog.ghostify.player.core.QueueItem
import java.io.File

/**
 * [MediaLibraryService.MediaLibrarySession.Callback] that exposes the Ghostify library tree to
 * Android Auto, Wear, and any other media browser client.
 *
 * Browse hierarchy:
 * ```
 * Root (MEDIA_ID_ROOT)
 *   └── Playlists (MEDIA_ID_PLAYLISTS)
 *         ├── Playlist 1 (playlist:<id>)
 *         │     ├── Song 1 (song:<id>)
 *         │     └── Song 2 (song:<id>)
 *         └── Playlist 2 (playlist:<id>)
 *               └── Song 3 (song:<id>)
 * ```
 *
 * Only DOWNLOADED songs with a valid file path are exposed.
 */
class GhostifyMediaLibraryCallback(
    private val appContext: Context,
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val container get() = (appContext as GhostifyApplication).container

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Timber.i("GhostifyMediaLibraryCallback.onGetLibraryRoot")
        val rootItem = MediaItem.Builder()
            .setMediaId(MEDIA_ID_ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        Timber.i("GhostifyMediaLibraryCallback.onGetChildren: parentId=$parentId, page=$page, pageSize=$pageSize")
        try {
            val children = when (parentId) {
                MEDIA_ID_ROOT -> getRootChildren()
                MEDIA_ID_PLAYLISTS -> getPlaylistChildren(page, pageSize)
                else -> {
                    if (parentId.startsWith(PLAYLIST_PREFIX)) {
                        val playlistId = parentId.removePrefix(PLAYLIST_PREFIX)
                        getSongChildren(playlistId, page, pageSize)
                    } else {
                        Timber.w("onGetChildren: unknown parentId=$parentId")
                        ImmutableList.of()
                    }
                }
            }
            Timber.i("GhostifyMediaLibraryCallback.onGetChildren: returning ${children.size} items")
            LibraryResult.ofItemList(children, null)
        } catch (t: Throwable) {
            Timber.e(t, "onGetChildren failed for parentId=$parentId")
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        Timber.i("GhostifyMediaLibraryCallback.onGetItem: mediaId=$mediaId")
        try {
            val item = when {
                mediaId == MEDIA_ID_ROOT -> {
                    MediaItem.Builder()
                        .setMediaId(MEDIA_ID_ROOT)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .build(),
                        )
                        .build()
                }
                mediaId.startsWith(SONG_PREFIX) -> {
                    val songId = mediaId.removePrefix(SONG_PREFIX)
                    container.songRepository.getSong(songId)?.toBrowseMediaItem()
                }
                mediaId.startsWith(PLAYLIST_PREFIX) -> {
                    val playlistId = mediaId.removePrefix(PLAYLIST_PREFIX)
                    container.playlistRepository.getPlaylist(playlistId)?.toBrowseMediaItem()
                }
                else -> null
            }
            if (item != null) {
                LibraryResult.ofItem(item, null)
            } else {
                Timber.w("onGetItem: item not found for mediaId=$mediaId")
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
        } catch (t: Throwable) {
            Timber.e(t, "onGetItem failed for mediaId=$mediaId")
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }
    }

    override fun onAddMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future {
        Timber.i("GhostifyMediaLibraryCallback.onAddMediaItems: ${mediaItems.size} items")
        val resolved = ArrayList<MediaItem>()
        for (mediaItem in mediaItems) {
            val mediaId = mediaItem.mediaId
            when {
                mediaId.startsWith(SONG_PREFIX) -> {
                    val songId = mediaId.removePrefix(SONG_PREFIX)
                    val song = container.songRepository.getSong(songId)
                    if (song != null && song.status == SongStatus.DOWNLOADED && !song.filePath.isNullOrBlank()) {
                        val file = File(song.filePath)
                        if (file.exists() && file.isFile) {
                            resolved.add(buildPlayableMediaItem(song, resolved.size))
                        } else {
                            Timber.w("onAddMediaItems: file not found for ${song.filePath}")
                        }
                    } else {
                        Timber.w("onAddMediaItems: song not found or not downloaded for mediaId=$mediaId")
                    }
                }
                mediaId.startsWith(PLAYLIST_PREFIX) -> {
                    val playlistId = mediaId.removePrefix(PLAYLIST_PREFIX)
                    val songs = container.playlistRepository.getSongs(playlistId)
                    Timber.i("onAddMediaItems: playlist $playlistId has ${songs.size} songs")
                    for (song in songs) {
                        if (song.status == SongStatus.DOWNLOADED && !song.filePath.isNullOrBlank()) {
                            val file = File(song.filePath)
                            if (file.exists() && file.isFile) {
                                resolved.add(buildPlayableMediaItem(song, resolved.size))
                            } else {
                                Timber.w("onAddMediaItems: file not found for ${song.filePath}")
                            }
                        }
                    }
                }
                else -> {
                    Timber.w("onAddMediaItems: unhandled mediaId=$mediaId, skipping")
                }
            }
        }
        Timber.i("onAddMediaItems: resolved ${resolved.size} of ${mediaItems.size} items")
        resolved
    }

    /**
     * Builds a playable [MediaItem] using [MediaItemMapper] so the resolved item is identical
     * to what the phone app produces.  Artwork is extracted only for the first item to avoid
     * blocking the callback; subsequent items rely on the controller's artwork backfill.
     */
    private suspend fun buildPlayableMediaItem(
        song: xyz.botolog.ghostify.data.db.entity.SongEntity,
        indexInQueue: Int,
    ): MediaItem {
        val artworkUri = song.coverArtLocalPath?.let { path ->
            val file = File(path)
            if (file.exists() && file.isFile) Uri.fromFile(file) else null
        }
        val queueItem = QueueItem(
            songId = song.id,
            title = song.title,
            artist = song.artists,
            album = song.album,
            durationMs = song.durationMs.toLong(),
            filePath = song.filePath!!,
            indexInQueue = indexInQueue,
            coverUrl = song.coverUrl,
            lyrics = song.lyrics,
        )
        return MediaItemMapper.toMediaItem(queueItem, null, artworkUri)
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        controller: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        Timber.i("onSearch: query='$query'")
        return scope.future {
            try {
                val songs = if (query.isBlank()) {
                    getAllDownloadedSongs().shuffled()
                } else {
                    searchSongs(query)
                }
                if (songs.isEmpty()) {
                    Timber.i("onSearch: no songs found for query='$query'")
                    return@future LibraryResult.ofVoid(params)
                }
                val mediaItems = songs.map { song ->
                    val artworkUri = song.coverArtLocalPath?.let { path ->
                        val file = File(path)
                        if (file.exists() && file.isFile) Uri.fromFile(file) else null
                    }
                    val queueItem = QueueItem(
                        songId = song.id,
                        title = song.title,
                        artist = song.artists,
                        album = song.album,
                        durationMs = song.durationMs.toLong(),
                        filePath = song.filePath!!,
                        indexInQueue = 0,
                        coverUrl = song.coverUrl,
                        lyrics = song.lyrics,
                    )
                    MediaItemMapper.toMediaItem(queueItem, null, artworkUri)
                }
                session.player.setMediaItems(mediaItems)
                session.player.prepare()
                session.player.play()
                LibraryResult.ofVoid(params)
            } catch (t: Throwable) {
                Timber.e(t, "onSearch failed")
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
            }
        }
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Timber.i("onGetSearchResult: query='$query', page=$page, pageSize=$pageSize")
        return scope.future {
            try {
                val songs = if (query.isBlank()) {
                    getAllDownloadedSongs()
                } else {
                    searchSongs(query)
                }
                val items = songs
                    .drop(page * pageSize)
                    .take(pageSize)
                    .map { it.toBrowseMediaItem() }
                    .let { ImmutableList.copyOf(it) }
                LibraryResult.ofItemList(items, params)
            } catch (t: Throwable) {
                Timber.e(t, "onGetSearchResult failed")
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
            }
        }
    }

    // ── Browse tree helpers ──────────────────────────────────────────────

    private fun getRootChildren(): ImmutableList<MediaItem> {
        val playlistsItem = MediaItem.Builder()
            .setMediaId(MEDIA_ID_PLAYLISTS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Playlists")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                    .build(),
            )
            .build()
        return ImmutableList.of(playlistsItem)
    }

    private suspend fun getPlaylistChildren(page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val allPlaylists = container.playlistRepository.playlistDao.observeAll()
            .firstOrNull() ?: emptyList()
        return allPlaylists
            .drop(page * pageSize)
            .take(pageSize)
            .map { it.toBrowseMediaItem() }
            .let { ImmutableList.copyOf(it) }
    }

    private suspend fun getSongChildren(playlistId: String, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val songs = container.playlistRepository.getSongs(playlistId)
        return songs
            .filter { it.status == SongStatus.DOWNLOADED && !it.filePath.isNullOrBlank() }
            .drop(page * pageSize)
            .take(pageSize)
            .map { it.toBrowseMediaItem() }
            .let { ImmutableList.copyOf(it) }
    }

    // ── Entity -> MediaItem mapping ──────────────────────────────────────

    private fun xyz.botolog.ghostify.data.db.entity.PlaylistEntity.toBrowseMediaItem(): MediaItem {
        val mediaId = "$PLAYLIST_PREFIX$id"
        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build(),
            )
        coverArtLocalPath?.let { path ->
            val file = File(path)
            if (file.exists() && file.isFile) {
                builder.setMediaMetadata(
                    builder.build().mediaMetadata.buildUpon()
                        .setArtworkUri(Uri.fromFile(file))
                        .build(),
                )
            }
        }
        return builder.build()
    }

    private fun xyz.botolog.ghostify.data.db.entity.SongEntity.toBrowseMediaItem(): MediaItem {
        val mediaId = "$SONG_PREFIX$id"
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artists)
            .setAlbumTitle(album)
            .setDurationMs(durationMs.toLong())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        coverArtLocalPath?.let { path ->
            val file = File(path)
            if (file.exists() && file.isFile) {
                metadataBuilder.setArtworkUri(Uri.fromFile(file))
            }
        }
        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(metadataBuilder.build())
        filePath?.let {
            val file = File(it)
            if (file.exists() && file.isFile) {
                builder.setUri(Uri.fromFile(file))
            }
        }
        return builder.build()
    }

    // ── Search helpers ───────────────────────────────────────────────

    private suspend fun getAllDownloadedSongs(): List<xyz.botolog.ghostify.data.db.entity.SongEntity> {
        val playlists = container.playlistRepository.playlistDao.observeAll()
            .firstOrNull() ?: emptyList()
        val allSongs = mutableListOf<xyz.botolog.ghostify.data.db.entity.SongEntity>()
        for (playlist in playlists) {
            val songs = container.playlistRepository.getSongs(playlist.id)
            allSongs.addAll(songs.filter {
                it.status == SongStatus.DOWNLOADED && !it.filePath.isNullOrBlank()
            })
        }
        return allSongs
    }

    private suspend fun searchSongs(query: String): List<xyz.botolog.ghostify.data.db.entity.SongEntity> {
        val matches = container.songRepository.songDao.searchByTitleOrArtist(query)
        return matches.filter {
            it.status == SongStatus.DOWNLOADED && !it.filePath.isNullOrBlank()
        }
    }

    companion object {
        const val MEDIA_ID_ROOT = "root"
        const val MEDIA_ID_PLAYLISTS = "playlists"
        const val PLAYLIST_PREFIX = "playlist:"
        const val SONG_PREFIX = "song:"
    }
}
