package xyz.botolog.ghostify.player

import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.SongStatus
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.player.core.RepeatMode
import xyz.botolog.ghostify.player.core.Song
import timber.log.Timber

/**
 * Persists and restores player state across app restarts.
 *
 * Stores the current song, playlist, queue order, shuffle/repeat/volume settings,
 * and playback position using the Room settings key-value store. On restore,
 * rebuilds the player queue from the database and seeks to the saved position.
 *
 * @property settings the key-value settings store.
 * @property songDao DAO for looking up songs by ID during restore.
 */
class PlayerStatePersistence(
    private val settings: SettingsRepository,
    private val songDao: SongDao,
) {

    companion object {
        private const val KEY_CURRENT_SONG_ID = "player_current_song_id"
        private const val KEY_PLAYLIST_ID = "player_playlist_id"
        private const val KEY_QUEUE_SONG_IDS = "player_queue_song_ids"
        private const val KEY_SHUFFLE_ENABLED = "player_shuffle_enabled"
        private const val KEY_REPEAT_MODE = "player_repeat_mode"
        private const val KEY_VOLUME = "player_volume"
        private const val KEY_POSITION_MS = "player_position_ms"
        private const val KEY_WAS_PLAYING = "player_was_playing"
    }

    /**
     * Saved player state loaded from the database.
     *
     * @property currentSongId the song that was playing, or null.
     * @property playlistId the playlist that was active, or null.
     * @property queueSongIds ordered list of song IDs in the queue.
     * @property shuffleEnabled whether shuffle was on.
     * @property repeatMode the repeat mode.
     * @property volume the volume level (0.0–1.0).
     * @property positionMs playback position in milliseconds.
     * @property wasPlaying whether playback was active.
     */
    data class SavedState(
        val currentSongId: String?,
        val playlistId: String?,
        val queueSongIds: List<String>,
        val shuffleEnabled: Boolean,
        val repeatMode: RepeatMode,
        val volume: Float,
        val positionMs: Long,
        val wasPlaying: Boolean,
    )

    /**
     * Saves the current player state.
     *
     * @param currentSongId the currently playing song's ID.
     * @param playlistId the active playlist's ID.
     * @param queueSongIds ordered list of song IDs in the current queue.
     * @param queueIndex the current index within the queue.
     * @param shuffleEnabled whether shuffle mode is active.
     * @param repeatMode the current repeat mode.
     * @param volume the current volume (0.0–1.0).
     * @param positionMs the current playback position.
     * @param wasPlaying whether the player is currently playing.
     */
    suspend fun save(
        currentSongId: String?,
        playlistId: String?,
        queueSongIds: List<String>,
        queueIndex: Int,
        shuffleEnabled: Boolean,
        repeatMode: RepeatMode,
        volume: Float,
        positionMs: Long,
        wasPlaying: Boolean,
    ) {
        Timber.i("PlayerStatePersistence.save: songId=$currentSongId, playlistId=$playlistId, queueSize=${queueSongIds.size}, index=$queueIndex")
        settings.set(KEY_CURRENT_SONG_ID, currentSongId ?: "")
        settings.set(KEY_PLAYLIST_ID, playlistId ?: "")
        settings.set(KEY_QUEUE_SONG_IDS, encodeQueue(queueSongIds, queueIndex))
        settings.set(KEY_SHUFFLE_ENABLED, shuffleEnabled.toString())
        settings.set(KEY_REPEAT_MODE, repeatMode.media3Value.toString())
        settings.set(KEY_VOLUME, volume.toString())
        settings.set(KEY_POSITION_MS, positionMs.toString())
        settings.set(KEY_WAS_PLAYING, wasPlaying.toString())
    }

    /**
     * Loads the saved player state from the database.
     *
     * @return the saved state, or null if no state has been saved.
     */
    suspend fun load(): SavedState? {
        Timber.i("PlayerStatePersistence.load: START")
        val songId = settings.get(KEY_CURRENT_SONG_ID, "")
        if (songId.isBlank()) {
            Timber.i("PlayerStatePersistence.load: no saved state")
            return null
        }
        val playlistId = settings.get(KEY_PLAYLIST_ID, "")
        val queueRaw = settings.get(KEY_QUEUE_SONG_IDS, "")
        val shuffleEnabled = settings.get(KEY_SHUFFLE_ENABLED, "false").toBooleanStrictOrNull() ?: false
        val repeatMode = settings.get(KEY_REPEAT_MODE, "0").toIntOrNull()?.let { RepeatMode.fromMedia3(it) } ?: RepeatMode.OFF
        val volume = settings.get(KEY_VOLUME, "1.0").toFloatOrNull() ?: 1f
        val positionMs = settings.get(KEY_POSITION_MS, "0").toLongOrNull() ?: 0L
        val wasPlaying = settings.get(KEY_WAS_PLAYING, "false").toBooleanStrictOrNull() ?: false

        val (queueSongIds, queueIndex) = decodeQueue(queueRaw)

        Timber.i("PlayerStatePersistence.load: songId=$songId, queueSize=${queueSongIds.size}, index=$queueIndex")
        return SavedState(
            currentSongId = songId,
            playlistId = playlistId.ifBlank { null },
            queueSongIds = queueSongIds,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            volume = volume,
            positionMs = positionMs,
            wasPlaying = wasPlaying,
        )
    }

    /**
     * Rebuilds player [Song]s from the saved queue song IDs.
     *
     * Only returns songs that are DOWNLOADED with a valid file path. Filters
     * out any songs that were deleted or are no longer playable.
     *
     * @param queueSongIds the saved queue song IDs.
     * @return list of [Song] objects in queue order, with only playable songs.
     */
    suspend fun restoreSongs(queueSongIds: List<String>): List<Song> {
        Timber.i("PlayerStatePersistence.restoreSongs: restoring ${queueSongIds.size} songs")
        val songs = mutableListOf<Song>()
        for (id in queueSongIds) {
            val entity = songDao.getById(id) ?: continue
            if (entity.status == SongStatus.DOWNLOADED && !entity.filePath.isNullOrBlank()) {
                songs.add(entity.toSong())
            }
        }
        Timber.i("PlayerStatePersistence.restoreSongs: ${songs.size}/${queueSongIds.size} songs restored")
        return songs
    }

    /**
     * Clears all saved player state.
     */
    suspend fun clear() {
        Timber.i("PlayerStatePersistence.clear: START")
        settings.set(KEY_CURRENT_SONG_ID, "")
        settings.set(KEY_PLAYLIST_ID, "")
        settings.set(KEY_QUEUE_SONG_IDS, "")
        settings.set(KEY_SHUFFLE_ENABLED, "false")
        settings.set(KEY_REPEAT_MODE, "0")
        settings.set(KEY_VOLUME, "1.0")
        settings.set(KEY_POSITION_MS, "0")
        settings.set(KEY_WAS_PLAYING, "false")
    }

    /**
     * Encodes the queue song IDs and current index into a single string.
     *
     * Format: "index:_songId1,songId2,songId3"
     */
    private fun encodeQueue(songIds: List<String>, currentIndex: Int): String {
        if (songIds.isEmpty()) return ""
        return "$currentIndex:${songIds.joinToString(",")}"
    }

    /**
     * Decodes a queue string back into song IDs and the current index.
     *
     * @param raw the encoded queue string.
     * @return pair of (song IDs list, current index).
     */
    private fun decodeQueue(raw: String): Pair<List<String>, Int> {
        if (raw.isBlank()) return emptyList<String>() to 0
        val colonIndex = raw.indexOf(':')
        if (colonIndex < 0) return raw.split(",").filter { it.isNotBlank() } to 0
        val index = raw.substring(0, colonIndex).toIntOrNull() ?: 0
        val ids = raw.substring(colonIndex + 1).split(",").filter { it.isNotBlank() }
        return ids to index
    }

    /**
     * Maps a Room [SongEntity] to the player core [Song] model.
     */
    private fun SongEntity.toSong(): Song = Song(
        id = id,
        title = title,
        artists = artists,
        album = album.orEmpty(),
        durationMs = durationMs.toLong(),
        filePath = filePath,
        status = xyz.botolog.ghostify.player.core.SongStatus.DOWNLOADED,
        coverUrl = coverUrl,
    )
}
