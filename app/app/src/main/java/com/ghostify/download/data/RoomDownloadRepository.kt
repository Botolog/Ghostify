package com.ghostify.download.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Room
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId ORDER BY position ASC")
    suspend fun songsFor(playlistId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId ORDER BY position ASC")
    fun observeSongs(playlistId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSong(songId: String): SongEntity?

    @Query(
        "UPDATE songs SET status = :status, error = :error, " +
            "file_path = COALESCE(:filePath, file_path) WHERE id = :songId",
    )
    suspend fun setStatus(songId: String, status: String, filePath: String?, error: String?)

    @Query("UPDATE songs SET status = :status WHERE id IN (:songIds)")
    suspend fun setStatuses(songIds: List<String>, status: String)

    @Query("SELECT status FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistStatus(playlistId: String): String?

    @Query("UPDATE playlists SET status = :status WHERE id = :playlistId")
    suspend fun setPlaylistStatus(playlistId: String, status: String)

    @Query("SELECT id FROM playlists")
    suspend fun allPlaylistIds(): List<String>
}

@Database(
    entities = [SongEntity::class, PlaylistEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DownloadDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: DownloadDatabase? = null

        fun get(context: Context): DownloadDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DownloadDatabase::class.java,
                    "ghostify_download.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}

/**
 * Room-backed implementation of [com.ghostify.download.DownloadRepository].
 * Status transitions are validated through the state machine so an illegal write
 * surfaces as an [com.ghostify.download.IllegalStateTransition] instead of
 * silently corrupting the database (T-044).
 */
class RoomDownloadRepository(private val dao: DownloadDao) : com.ghostify.download.DownloadRepository {

    override suspend fun songsFor(playlistId: String): List<com.ghostify.download.SongRecord> =
        dao.songsFor(playlistId).map { it.toRecord() }

    override fun observeSongs(playlistId: String): Flow<List<com.ghostify.download.SongRecord>> =
        dao.observeSongs(playlistId).map { list -> list.map { it.toRecord() } }

    override suspend fun getSong(songId: String): com.ghostify.download.SongRecord? =
        dao.getSong(songId)?.toRecord()

    override suspend fun setStatus(
        songId: String,
        status: com.ghostify.download.DownloadStatus,
        filePath: String?,
        error: String?,
    ) {
        val current = dao.getSong(songId)
        if (current != null) {
            com.ghostify.download.SongStateMachine.requireTransition(
                com.ghostify.download.DownloadStatus.valueOf(current.status),
                status,
            )
        }
        dao.setStatus(songId, status.name, filePath, error)
    }

    override suspend fun setStatuses(
        songIds: Collection<String>,
        status: com.ghostify.download.DownloadStatus,
    ) {
        dao.setStatuses(songIds.toList(), status.name)
    }

    override suspend fun getPlaylistStatus(playlistId: String): com.ghostify.download.PlaylistStatus? =
        dao.getPlaylistStatus(playlistId)?.let { com.ghostify.download.PlaylistStatus.valueOf(it) }

    override suspend fun setPlaylistStatus(playlistId: String, status: com.ghostify.download.PlaylistStatus) {
        dao.setPlaylistStatus(playlistId, status.name)
    }

    override suspend fun allPlaylistIds(): List<String> = dao.allPlaylistIds()
}
