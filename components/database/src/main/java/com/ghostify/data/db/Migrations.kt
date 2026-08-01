package com.ghostify.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history.
 *
 * v1 — initial release: `playlists` + `songs` (no `settings` table, no `yt_id`
 *      column, songs indexed by a plain `playlist_id` index only).
 * v2 — adds:
 *      - `songs.yt_id` (YouTube id resolved during download);
 *      - `UNIQUE(playlist_id, spotify_id)` so re-syncs cannot duplicate tracks;
 *      - composite indexes `(playlist_id, position)` and `(playlist_id, status)`
 *        for the ordered track list and status-filtered queries;
 *      - the `settings` key/value table.
 *
 * The migration is a single Room transaction, so it is atomic: if any step fails
 * SQLite rolls the whole upgrade back and the database is left at v1.
 *
 * The statements are kept in one place ([MIGRATION_1_2_STATEMENTS]) so the Room
 * [Migration] and the JVM (sqlite-jdbc) migration test share the exact same SQL.
 */
object Migrations {

    /** The v1 -> v2 DDL, in dependency order. */
    val MIGRATION_1_2_STATEMENTS: List<String> = listOf(
        // 1. New column — nullable, so no backfill of existing rows is required.
        "ALTER TABLE songs ADD COLUMN yt_id TEXT",
        // 2. Replace the coarse playlist index with targeted composite indexes.
        "DROP INDEX index_songs_playlist_id",
        "CREATE UNIQUE INDEX index_songs_playlist_id_spotify_id ON songs (playlist_id, spotify_id)",
        "CREATE INDEX index_songs_playlist_id_position ON songs (playlist_id, position)",
        "CREATE INDEX index_songs_playlist_id_status ON songs (playlist_id, status)",
        // 3. Settings key/value store.
        "CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
            "PRIMARY KEY(`key`))"
    )

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_STATEMENTS.forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
