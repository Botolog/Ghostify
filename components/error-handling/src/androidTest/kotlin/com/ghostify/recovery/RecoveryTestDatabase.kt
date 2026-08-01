package com.ghostify.recovery

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

/**
 * Minimal in-memory Room schema mirroring PROJECT.md §4 (just the columns the recovery
 * logic reads). Room's column-name mapping lets [RoomRecoveryDao] run against it, so the
 * device tests exercise the real generated SQL — the same queries the production DAO uses.
 *
 * DEVICE-ONLY: compiled and run by the Android Gradle plugin (`connectedAndroidTest`),
 * not by the JVM harness in `run-tests.sh`.
 */
@Entity(tableName = "songs")
data class SongRow(
    @PrimaryKey val id: String,
    val playlist_id: String? = null,
    val status: String,
)

@Entity(tableName = "playlists")
data class PlaylistRow(
    @PrimaryKey val id: String,
    val status: String,
)

@Database(entities = [SongRow::class, PlaylistRow::class], version = 1, exportSchema = false)
abstract class RecoveryTestDatabase : RoomDatabase() {
    abstract fun recoveryDao(): RoomRecoveryDao
}
