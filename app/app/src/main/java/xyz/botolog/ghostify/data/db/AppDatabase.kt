package xyz.botolog.ghostify.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SettingDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.db.entity.SettingEntity
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.recovery.RoomRecoveryDao
import timber.log.Timber

@Database(
    entities = [PlaylistEntity::class, SongEntity::class, SettingEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun songDao(): SongDao
    abstract fun settingDao(): SettingDao
    abstract fun recoveryDao(): RoomRecoveryDao

    /** Atomic multi-statement writes backed by SQLite transactions. */
    fun transactionRunner(): TransactionRunner {
        Timber.i("AppDatabase.transactionRunner: START")
        val result = AppDatabaseTransactionRunner(this)
        Timber.i("AppDatabase.transactionRunner: returning $result")
        return result
    }

    companion object {

        /** The one file-backed instance used by the whole app. */
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            Timber.i("AppDatabase.get: START")
            val result = instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(*Migrations.ALL).build().also { instance = it }
            }
            Timber.i("AppDatabase.get: returning $result")
            return result
        }

        /**
         * In-memory database for tests and previews. Note: intentionally not exposed to
         * production callers — the real app opens a file-backed database.
         */
        fun inMemory(context: Context): AppDatabase {
            Timber.i("AppDatabase.inMemory: START")
            val result = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addMigrations(*Migrations.ALL)
                .build()
            Timber.i("AppDatabase.inMemory: returning $result")
            return result
        }

        private const val DB_NAME = "ghostify.db"
    }
}

/**
 * Bridges [AppDatabase] to [TransactionRunner]. Kept as a separate adapter so the
 * member `withTransaction` cannot shadow `androidx.room.withTransaction`.
 */
private class AppDatabaseTransactionRunner(private val db: AppDatabase) : TransactionRunner {
    override suspend fun <R> withinTransaction(block: suspend () -> R): R {
        Timber.i("AppDatabaseTransactionRunner.withinTransaction: START")
        val result = db.withTransaction { block() }
        Timber.i("AppDatabaseTransactionRunner.withinTransaction: returning $result")
        return result
    }
}
