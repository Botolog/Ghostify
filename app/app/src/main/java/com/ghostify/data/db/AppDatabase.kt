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
import com.ghostify.recovery.RoomRecoveryDao

@Database(
    entities = [PlaylistEntity::class, SongEntity::class, SettingEntity::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun songDao(): SongDao
    abstract fun settingDao(): SettingDao
    abstract fun recoveryDao(): RoomRecoveryDao

    /** Atomic multi-statement writes backed by SQLite transactions. */
    fun transactionRunner(): TransactionRunner = AppDatabaseTransactionRunner(this)

    companion object {

        /** The one file-backed instance used by the whole app. */
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(*Migrations.ALL).build().also { instance = it }
            }

        /**
         * In-memory database for tests and previews. Note: intentionally not exposed to
         * production callers — the real app opens a file-backed database.
         */
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addMigrations(*Migrations.ALL)
                .build()

        private const val DB_NAME = "ghostify.db"
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
