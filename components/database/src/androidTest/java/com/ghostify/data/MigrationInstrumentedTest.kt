package com.ghostify.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghostify.data.db.AppDatabase
import com.ghostify.data.db.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented T-074: real v1 database on disk seeded with rows, upgraded through
 * the v1->v2 migration, validated against the compiled schema, then asserted.
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate1To2PreservesData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)

        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO playlists (id, spotify_id, name, track_count, status, created_at) " +
                    "VALUES ('p1', 'spot-1', 'Old', 2, 'NEW', 1000)"
            )
            db.execSQL(
                "INSERT INTO songs (id, playlist_id, spotify_id, title, artists, duration_ms, status, position, added_at) " +
                    "VALUES ('s1', 'p1', 'trk-1', 'One', 'Art', 1000, 'PENDING', 0, 1000)"
            )
            db.execSQL(
                "INSERT INTO songs (id, playlist_id, spotify_id, title, artists, duration_ms, status, position, added_at) " +
                    "VALUES ('s2', 'p1', 'trk-2', 'Two', 'Art', 2000, 'PENDING', 1, 1001)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, *Migrations.ALL).close()

        val appDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.ALL)
            .build()
        runBlocking {
            assertEquals("Old", appDb.playlistDao().getById("p1")?.name)
            assertEquals(2, appDb.songDao().getSongsForPlaylist("p1").size)
        }
        appDb.close()
        context.deleteDatabase(TEST_DB)
    }

    companion object {
        private const val TEST_DB = "migration-instrumented-db"
    }
}
