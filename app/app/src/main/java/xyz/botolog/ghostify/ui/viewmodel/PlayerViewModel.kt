package xyz.botolog.ghostify.ui.viewmodel

import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.player.PlayerController
import xyz.botolog.ghostify.player.MediaItemMapper
import xyz.botolog.ghostify.player.core.CurrentItem
import xyz.botolog.ghostify.player.core.PlayerUiState as CorePlayerUiState
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlayerContract.PlayerUiState
import xyz.botolog.ghostify.ui.contract.PlayerContract.SongMetadataUpdateResult
import xyz.botolog.ghostify.ui.model.NowPlaying
import xyz.botolog.ghostify.ui.model.QueueItem
import xyz.botolog.ghostify.ui.playlist.PlaylistSortSpec
import xyz.botolog.ghostify.ui.playlist.isStoredOrder
import xyz.botolog.ghostify.ui.playlist.sortedTrackIds
import xyz.botolog.ghostify.ui.playlist.storedSortSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import timber.log.Timber

/**
 * Backs the Player screen: a pure presentation layer over [PlayerController].
 *
 * It maps the raw player state stream onto the render-ready [PlayerUiState]
 * (now-playing / position / shuffle / repeat / queue / lyrics) and forwards user
 * commands. It never owns playback state itself.
 *
 * Saving a metadata edit is the one command that reaches past the player into the
 * database twice: it writes the song row, then re-applies the sort the song's playlist
 * was saved with so the edited track lands where that sort says it should. That re-sort
 * only touches `songs.position` — the playback queue keeps the order it was built with.
 *
 * @property player the underlying media playback controller.
 * @property songDao DAO for observing lyrics from the database.
 * @property downloads download manager for retry-lyrics support.
 * @property playlistRepo playlist persistence layer, for the saved-sort re-application.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val player: PlayerController,
    private val songDao: SongDao,
    private val downloads: DownloadManager,
    private val settingsRepository: SettingsRepository,
    private val playlistRepo: PlaylistRepository,
) : ContractViewModel(), PlayerContract {

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Mutable flag that tracks whether the queue drawer is open. */
    private val queueOpen = MutableStateFlow(false)

    /** Current lyrics for the active track, observed reactively from the DB. */
    private val currentLyrics = MutableStateFlow<String?>(null)

    /** Current song entity for the active track, observed reactively from the DB. */
    private val currentSongEntity = MutableStateFlow<xyz.botolog.ghostify.data.db.entity.SongEntity?>(null)

    /** Current song id — drives the lyrics observation switch. */
    private val currentSongId = MutableStateFlow<String?>(null)

    /** Cached media id of the current track to avoid re-extracting artwork on every tick. */
    private var cachedMediaId: String? = null

    /** Cached cover art bytes for the current track. */
    private var cachedCover: Any? = null

    init {
        Timber.i("PlayerViewModel: init")

        // Observe lyrics reactively — switch to a new Flow whenever the current track changes.
        launch {
            currentSongId
                .flatMapLatest { id ->
                    if (id != null) songDao.observeLyricsById(id) else emptyFlow()
                }
                .catch { e -> Timber.e(e, "PlayerViewModel: lyrics stream FAILED") }
                .collect { lyrics ->
                    currentLyrics.value = lyrics
                }
        }

        // Observe full song entity reactively — switch to a new Flow whenever the current track changes.
        launch {
            currentSongId
                .flatMapLatest { id ->
                    if (id != null) songDao.observeById(id) else emptyFlow()
                }
                .catch { e -> Timber.e(e, "PlayerViewModel: songEntity stream FAILED") }
                .collect { entity ->
                    currentSongEntity.value = entity
                }
        }

        // Observe player state, queue flag, lyrics, song entity, AND showQueueCovers — combine all into one emission.
        launch {
            combine(player.state, queueOpen, currentLyrics, currentSongEntity, settingsRepository.observeShowQueueCovers()) { core, open, lyrics, entity, showQueueCovers ->
                val ui = mapToUi(core, open, entity, showQueueCovers)
                val mediaId = ui.nowPlaying?.let { findMediaId(ui) }
                currentSongId.value = mediaId
                ui.copy(
                    lyrics = lyrics,
                    lyricsSource = entity?.lyricsSource,
                    lyricsEdited = entity?.lyricsEdited == true,
                )
            }
                .catch { e -> Timber.e(e, "PlayerViewModel: stream collection FAILED") }
                .collect { _state.value = it }
        }
    }

    override fun togglePlay() {
        Timber.i("PlayerViewModel.togglePlay: START")
        player.togglePlayPause()
    }

    override fun next() {
        Timber.i("PlayerViewModel.next: START")
        player.next()
    }

    override fun previous() {
        Timber.i("PlayerViewModel.previous: START")
        player.previous()
    }

    override fun previousTrack() {
        Timber.i("PlayerViewModel.previousTrack: START")
        player.skipToPreviousTrack()
    }

    override fun seekTo(positionMs: Long) {
        Timber.i("PlayerViewModel.seekTo: START")
        player.seekTo(positionMs)
    }

    override fun toggleShuffle() {
        Timber.i("PlayerViewModel.toggleShuffle: START")
        player.toggleShuffle()
    }

    override fun cycleRepeat() {
        Timber.i("PlayerViewModel.cycleRepeat: START")
        player.toggleRepeatMode()
    }

    override fun setVolume(fraction: Float) {
        Timber.i("PlayerViewModel.setVolume: START")
        player.setVolume(fraction)
    }

    override fun jumpToQueueIndex(index: Int) {
        Timber.i("PlayerViewModel.jumpToQueueIndex: START")
        player.skipToMediaItem(index)
    }

    override fun reorderQueue(fromIndex: Int, toIndex: Int) {
        Timber.i("PlayerViewModel.reorderQueue: from=$fromIndex, to=$toIndex")
        player.reorderQueue(fromIndex, toIndex)
    }

    override fun addToQueue(songId: String) {
        Timber.i("PlayerViewModel.addToQueue: songId=$songId")
        launch {
            val entity = songDao.getById(songId) ?: return@launch
            if (entity.status != xyz.botolog.ghostify.data.model.SongStatus.DOWNLOADED || entity.filePath.isNullOrBlank()) {
                Timber.w("addToQueue: song $songId is not downloaded, skipping")
                return@launch
            }
            val mediaItem = xyz.botolog.ghostify.player.MediaItemMapper.toMediaItem(
                xyz.botolog.ghostify.player.core.QueueItem(
                    songId = entity.id,
                    title = entity.title,
                    artist = entity.artists,
                    album = entity.album.orEmpty(),
                    durationMs = entity.durationMs.toLong(),
                    filePath = entity.filePath,
                    indexInQueue = 0,
                    coverUrl = entity.coverUrl,
                ),
                null,
            )
            player.addToQueueNext(songId, mediaItem)
        }
    }

    override fun toggleQueue() {
        Timber.i("PlayerViewModel.toggleQueue: START")
        queueOpen.update { !it }
    }

    override fun closeQueue() {
        Timber.i("PlayerViewModel.closeQueue: START")
        queueOpen.value = false
    }

    override fun retryLyrics() {
        Timber.i("PlayerViewModel.retryLyrics: START")
        val songId = currentSongId.value ?: return
        launch {
            try {
                downloads.retryLyricsForSong(songId)
            } catch (e: Exception) {
                Timber.e(e, "PlayerViewModel.retryLyrics: FAILED for songId=$songId")
            }
        }
    }

    override fun saveLyrics(lyrics: String, edited: Boolean) {
        Timber.i("PlayerViewModel.saveLyrics: START")
        val songId = currentSongId.value ?: run {
            Timber.w("PlayerViewModel.saveLyrics: no nowPlaying")
            return
        }
        launch {
            try {
                downloads.updateLyricsForSong(songId, lyrics)
                if (edited) {
                    downloads.markLyricsEdited(songId)
                }
                Timber.i("PlayerViewModel.saveLyrics: done")
            } catch (e: Exception) {
                Timber.e(e, "PlayerViewModel.saveLyrics: FAILED")
            }
        }
    }

    override fun updateSongMetadata(
        songId: String,
        title: String,
        artists: String,
        album: String,
        onResult: (SongMetadataUpdateResult) -> Unit,
    ) {
        Timber.i("PlayerViewModel.updateSongMetadata: songId=$songId")
        val id = songId.trim()
        val newTitle = title.trim()
        val newArtists = artists.trim()
        val newAlbum = album.trim().ifEmpty { null }
        if (id.isEmpty()) {
            Timber.w("PlayerViewModel.updateSongMetadata: no song id")
            onResult(SongMetadataUpdateResult.Failed(METADATA_SONG_MISSING))
            return
        }
        if (newTitle.isEmpty()) {
            onResult(SongMetadataUpdateResult.Failed(METADATA_TITLE_REQUIRED))
            return
        }
        launch {
            try {
                val written = songDao.updateMetadata(id, newTitle, newArtists, newAlbum)
                if (written <= 0) {
                    Timber.w("PlayerViewModel.updateSongMetadata: no row for $id")
                    onResult(SongMetadataUpdateResult.Failed(METADATA_SONG_MISSING))
                    return@launch
                }
                player.updateQueueItemMetadata(id, newTitle, newArtists, newAlbum)
                onResult(SongMetadataUpdateResult.Updated)
                reapplyPlaylistSortAfterEdit(id)
            } catch (e: Exception) {
                Timber.e(e, "PlayerViewModel.updateSongMetadata: FAILED for songId=$id")
                onResult(SongMetadataUpdateResult.Failed(METADATA_SAVE_FAILED))
            }
        }
    }

    /**
     * Moves a just-edited track to where the playlist's saved sort says it belongs.
     *
     * A playlist is sorted by writing `songs.position`, so renaming a track can leave it
     * sitting in the wrong place: "Alpha" renamed to "Zulu" in an A–Z playlist is still at
     * the top. Re-applying the sort after the write moves it. The sort is read from the
     * playlist's own row, so the track lands where the *playlist* is sorted, whether or not
     * the detail screen is open.
     *
     * Nothing happens for a playlist in its own ascending order — the default, whose
     * positions are the order the user dragged them into — or for a stored field this build
     * does not know: an edit must never rewrite a manual order. A direction the user did
     * choose (playlist order descending, say) is a real sort, so it is re-applied like any
     * other. The order is always recomputed from a fresh read of the whole playlist and
     * written as one batch, so a concurrent edit or sort cannot lose a position.
     *
     * The playback queue is deliberately left alone — it keeps the order it was built with
     * until the playlist is started again. A failure here cannot undo the saved metadata,
     * so it is logged rather than reported back to the dialog.
     */
    private suspend fun reapplyPlaylistSortAfterEdit(songId: String) {
        try {
            val song = songDao.getById(songId) ?: return
            val playlist = playlistRepo.getPlaylist(song.playlistId) ?: return
            val specification = storedSortSpec(playlist.sortField, playlist.sortDescending)
            if (specification.isStoredOrder()) return
            val songs = playlistRepo.getSongs(playlist.id)
            if (songs.size < 2) return
            val orderedIds = sortedTrackIds(songs.map { it.toTrackUi() }, specification)
            val changed = playlistRepo.applySongOrder(playlist.id, orderedIds)
            Timber.i("PlayerViewModel.reapplyPlaylistSortAfterEdit: $specification changed=$changed")
        } catch (e: Exception) {
            Timber.e(e, "PlayerViewModel.reapplyPlaylistSortAfterEdit: FAILED for songId=$songId")
        }
    }

    override fun refetchLyrics(provider: String, onResult: (String?) -> Unit) {
        Timber.i("PlayerViewModel.refetchLyrics: START provider=$provider")
        val songId = currentSongId.value ?: run {
            Timber.w("PlayerViewModel.refetchLyrics: no nowPlaying")
            onResult(null)
            return
        }
        launch {
            try {
                val lyrics = downloads.fetchLyricsForSong(songId, provider)
                Timber.i("PlayerViewModel.refetchLyrics: got ${lyrics?.length ?: 0} chars")
                onResult(lyrics)
            } catch (e: Exception) {
                Timber.e(e, "PlayerViewModel.refetchLyrics: FAILED")
                onResult(null)
            }
        }
    }

    /**
     * Maps the raw core player state and queue-open flag to a render-ready [PlayerUiState].
     *
     * @param core the current state from the player core.
     * @param open whether the queue drawer is visible.
     */
    private fun mapToUi(
        core: CorePlayerUiState,
        open: Boolean,
        songEntity: xyz.botolog.ghostify.data.db.entity.SongEntity?,
        showQueueCovers: Boolean,
    ): PlayerUiState {
        val isEmpty = core.nothingToPlay || core.queue.isEmpty()
        val current = core.currentItem
        return PlayerUiState(
            empty = isEmpty,
            nowPlaying = if (isEmpty || current == null) null else toNowPlaying(current, songEntity),
            isPlaying = core.isPlaying,
            positionMs = core.positionMs,
            durationMs = core.durationMs,
            shuffle = core.shuffleEnabled,
            repeatMode = core.repeatMode.toUi(),
            volume = core.volume,
            queue = mapQueueItems(core),
            queueOpen = open,
            showQueueCovers = showQueueCovers,
        )
    }

    /**
     * Extracts the media id from the current state for lyrics observation.
     */
    private fun findMediaId(ui: PlayerUiState): String? {
        // The queue items carry the songId which matches the DB primary key.
        val idx = player.state.value.currentQueueIndex
        if (idx < 0 || idx >= player.state.value.queue.size) return null
        return player.state.value.queue[idx].songId
    }

    /**
     * Maps the core queue list to a list of render-ready [QueueItem]s.
     *
     * @param core the current state from the player core.
     */
    private fun mapQueueItems(core: CorePlayerUiState): List<QueueItem> =
        core.queue.mapIndexed { index, item ->
            QueueItem(
                songId = item.songId,
                title = item.title.orEmpty(),
                artist = item.artist.orEmpty(),
                durationMs = item.durationMs ?: DEFAULT_DURATION_MS,
                isCurrent = index == core.currentQueueIndex,
                queuedByUser = item.queuedByUser,
                coverUrl = item.coverUrl,
            )
        }

    /**
     * Converts a [CurrentItem] from the player core into a [NowPlaying] model.
     *
     * @param current the current item from the player core.
     */
    private fun toNowPlaying(
        current: CurrentItem,
        entity: xyz.botolog.ghostify.data.db.entity.SongEntity?,
    ): NowPlaying = NowPlaying(
        title = current.title.orEmpty(),
        artist = current.artist.orEmpty(),
        album = current.album.orEmpty(),
        coverUrl = coverFor(current),
        id = entity?.id.orEmpty(),
        spotifyId = entity?.spotifyId.orEmpty(),
        ytId = entity?.ytId,
        filePath = entity?.filePath,
        status = entity?.status?.name.orEmpty(),
        error = entity?.error,
        lyrics = entity?.lyrics,
        durationMs = entity?.durationMs?.toLong() ?: 0L,
        position = entity?.position ?: 0,
        lyricsSource = entity?.lyricsSource,
        lyricsEdited = entity?.lyricsEdited ?: false,
        ytUrl = entity?.ytUrl,
        ytName = entity?.ytName,
        ytChannel = entity?.ytChannel,
        bitrate = entity?.bitrate,
        fileSize = entity?.fileSize,
        downloadedAt = entity?.downloadedAt,
    )

    /**
     * Returns cover art for the current track. Passes the raw [ByteArray] to Coil
     * directly (more efficient than base64-encoding), falling back to the HTTP URL
     * from the database when embedded artwork is unavailable.
     *
     * @param current the current item from the player core.
     */
    private fun coverFor(current: CurrentItem): Any? {
        val mediaId = current.mediaId ?: return current.coverUrl
        val bytes = current.artworkBytes ?: return current.coverUrl
        if (cachedMediaId == mediaId) return cachedCover
        cachedMediaId = mediaId
        cachedCover = bytes
        return cachedCover
    }

    companion object {
        /** Default duration used when the player core reports `null`. */
        private const val DEFAULT_DURATION_MS = 0L

        /** Shown when the edited song id matches no row — the write is skipped entirely. */
        internal const val METADATA_SONG_MISSING = "This song is no longer in your library"

        /** Shown when the title field is empty, so there is nothing worth saving. */
        internal const val METADATA_TITLE_REQUIRED = "Enter a title before saving"

        /** Shown when the database write itself failed; the row is left untouched. */
        internal const val METADATA_SAVE_FAILED = "Couldn't save the changes. Please try again."
    }
}
