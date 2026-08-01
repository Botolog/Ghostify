package com.ghostify.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.ghostify.data.db.dao.PlaylistDao
import com.ghostify.data.db.dao.SettingDao
import com.ghostify.data.db.dao.SongDao
import com.ghostify.data.db.entity.PlaylistEntity
import com.ghostify.data.db.entity.SettingEntity
import com.ghostify.data.db.entity.SongEntity

@Database(
    entities = [PlaylistEntity::class, SongEntity::class, SettingEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun songDao(): SongDao
    abstract fun settingDao(): SettingDao

    /** Atomic multi-statement writes backed by SQLite transactions. */
    fun transactionRunner(): TransactionRunner = AppDatabaseTransactionRunner(this)

    companion object {

        /**
         * In-memory database for tests and previews. Note: intentionally not exposed to
         * production callers — the real app opens a file-backed database.
         */
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

/**
 * Bridges [AppDatabase] to [TransactionRunner]. Kept as a separate adapter so the
 * member `withTransaction` cannot shadow `androidx.room.withTransaction`.
 */
private class AppDatabaseTransactionRunner(private val db: AppDatabase) : TransactionRunner {
    override suspend fun <R> withinTransaction(block: suspend () -> R): R =
        db.withTransaction { block() }
}
