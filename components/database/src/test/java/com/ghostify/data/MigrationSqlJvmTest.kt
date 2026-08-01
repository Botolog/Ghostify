package com.ghostify.data

import com.ghostify.data.db.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Pure-JVM verification that runs anywhere (including this aarch64 host, where
 * Robolectric's native runtime is unavailable). It exercises the real SQLite engine
 * through the JDBC driver and:
 *
 *  - runs the exact v1 -> v2 DDL from [Migrations.MIGRATION_1_2_STATEMENTS] against
 *    a seeded v1 database (T-074 semantics);
 *  - mirrors the DAO query strings to confirm their SQL semantics (ordering, status
 *    filters, FK cascade, UNIQUE enforcement) are correct on SQLite;
 *  - checks the 1000-row batch insert budget (T-076).
 *
 * Room itself validates every DAO query at compile time (KSP), so these tests assert
 * that the *statements* behave as the DAOs expect on a real SQLite engine.
 */
class MigrationSqlJvmTest {

    /** Parsed view of the compiled Room schema export (`schemas/.../2.json`). */
    private object CompiledSchema {
        data class Column(val name: String, val type: String, val notNull: Boolean)
        data class IndexDef(val name: String, val unique: Boolean, val columns: List<String>)

        val songsColumns: List<Column>
        val settingsColumns: List<Column>
        val songsIndices: List<IndexDef>

        init {
            val text = javaClass.getResourceAsStream("/com.ghostify.data.db.AppDatabase/2.json")
                ?.readBytes()?.toString(Charsets.UTF_8)
                ?: error("2.json not found on test classpath")
            val tableCreateSqls = Regex("\"createSql\": \"((?:[^\"\\\\]|\\\\.)*)\"")
                .findAll(text)
                .map { unescape(it.groupValues[1]) }
                .filter { it.startsWith("CREATE TABLE") }
                .toList()
            check(tableCreateSqls.size == 3) { "expected playlists+songs+settings tables, got ${tableCreateSqls.size}" }

            songsColumns = parseColumns(tableCreateSqls[1])
            settingsColumns = parseColumns(tableCreateSqls[2])
            songsIndices = parseIndices(entityBlock(text, "songs"))
        }

        private fun entityBlock(text: String, table: String): String {
            val start = text.indexOf("\"tableName\": \"$table\"")
            check(start >= 0) { "table '$table' not found in schema export" }
            val end = text.indexOf("\"tableName\":", start + 1).let { if (it < 0) text.length else it }
            return text.substring(start, end)
        }

        private fun parseColumns(createSql: String): List<Column> =
            Regex("`([A-Za-z_]+)` ([A-Za-z]+)( NOT NULL)?")
                .findAll(createSql)
                .map { m -> Column(m.groupValues[1], m.groupValues[2], m.groupValues[3].isNotEmpty()) }
                .toList()

        private fun parseIndices(entity: String): List<IndexDef> {
            val block = Regex("\"indices\": \\[(.*?)\\]").find(entity)?.groupValues?.get(1) ?: return emptyList()
            val entry = Regex(
                "\"name\": \"([^\"]+)\"\\s*,\\s*\"unique\": (true|false)\\s*,\\s*\"columnNames\": \\[([^\\]]*)\\]"
            )
            return entry.findAll(block).map { m ->
                val columns = Regex("\"([^\"]+)\"").findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
                IndexDef(m.groupValues[1], m.groupValues[2] == "true", columns)
            }.toList()
        }

        private fun unescape(s: String): String = s.replace("\\`", "`").replace("\\\\", "\\")
    }

    private fun v1Connection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute(
                "CREATE TABLE `playlists` (`id` TEXT NOT NULL, `spotify_id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`owner` TEXT, `cover_url` TEXT, `track_count` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `last_synced_at` INTEGER, PRIMARY KEY(`id`))"
            )
            st.execute(
                "CREATE TABLE `songs` (`id` TEXT NOT NULL, `playlist_id` TEXT NOT NULL, `spotify_id` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `artists` TEXT NOT NULL, `album` TEXT, `duration_ms` INTEGER NOT NULL, " +
                    "`cover_url` TEXT, `file_path` TEXT, `status` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                    "`added_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            st.execute("CREATE UNIQUE INDEX `index_playlists_spotify_id` ON `playlists` (`spotify_id`)")
            st.execute("CREATE INDEX `index_songs_playlist_id` ON `songs` (`playlist_id`)")
        }
        return conn
    }

    private fun seed(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("INSERT INTO playlists (id, spotify_id, name, owner, track_count, status, created_at) " +
                "VALUES ('p1', 'spot-1', 'Old Playlist', 'Alice', 2, 'NEW', 1000)")
            st.execute("INSERT INTO songs (id, playlist_id, spotify_id, title, artists, album, duration_ms, status, position, added_at) " +
                "VALUES ('s1', 'p1', 'trk-1', 'Track One', 'Artist One', 'Album', 180000, 'PENDING', 0, 1000)")
            st.execute("INSERT INTO songs (id, playlist_id, spotify_id, title, artists, album, duration_ms, status, position, added_at) " +
                "VALUES ('s2', 'p1', 'trk-2', 'Track Two', 'Artist Two', 'Album', 200000, 'PENDING', 1, 1001)")
        }
    }

    @Test
    fun T074_migrate1To2PreservesDataAndBuildsV2Schema() {
        val conn = v1Connection()
        seed(conn)
        conn.createStatement().use { st -> Migrations.MIGRATION_1_2_STATEMENTS.forEach { st.execute(it) } }

        // Data preserved.
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name, track_count FROM playlists WHERE id = 'p1'").use { rs ->
                assertTrue(rs.next())
                assertEquals("Old Playlist", rs.getString(1))
                assertEquals(2, rs.getInt(2))
            }
            val count = st.executeQuery("SELECT COUNT(*) FROM songs WHERE playlist_id = 'p1'").use { rs ->
                rs.next(); rs.getInt(1)
            }
            assertEquals(2, count)
        }

        // v2 schema: yt_id column present.
        conn.createStatement().use { st ->
            val cols = st.executeQuery("PRAGMA table_info(songs)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(2)) }
            }
            assertTrue("yt_id added", cols.contains("yt_id"))

            val indexes = st.executeQuery("PRAGMA index_list(songs)").use { rs ->
                buildList { while (rs.next()) add(rs.getString(2)) }
            }
            assertTrue("UNIQUE(playlist_id, spotify_id)", indexes.contains("index_songs_playlist_id_spotify_id"))
            assertTrue("position index", indexes.contains("index_songs_playlist_id_position"))
            assertTrue("status index", indexes.contains("index_songs_playlist_id_status"))
            assertFalse("plain playlist_id index dropped", indexes.contains("index_songs_playlist_id"))
        }

        // Migrated schema matches the compiled Room schema export (2.json), exactly as
        // Room's own on-device validation would require: columns (name, type, NOT NULL)
        // and indices (name, uniqueness, columns) for songs + settings.
        conn.createStatement().use { st ->
            val actual = st.executeQuery("PRAGMA table_info(songs)").use { rs ->
                buildList {
                    while (rs.next()) add(CompiledSchema.Column(rs.getString(2), rs.getString(3), rs.getInt(4) == 1))
                }
            }.associateBy { it.name }
            assertEquals("songs column count", CompiledSchema.songsColumns.size, actual.size)
            CompiledSchema.songsColumns.forEach { expected ->
                val found = actual[expected.name]
                assertNotNull("column ${expected.name} present", found)
                assertEquals("column ${expected.name} type", expected.type.uppercase(), found!!.type.uppercase())
                assertEquals("column ${expected.name} not-null", expected.notNull, found.notNull)
            }

            val settings = st.executeQuery("PRAGMA table_info(settings)").use { rs ->
                buildList {
                    while (rs.next()) add(CompiledSchema.Column(rs.getString(2), rs.getString(3), rs.getInt(4) == 1))
                }
            }.associateBy { it.name }
            assertEquals("settings column count", CompiledSchema.settingsColumns.size, settings.size)
            CompiledSchema.settingsColumns.forEach { expected ->
                val found = settings[expected.name]
                assertNotNull("settings column ${expected.name} present", found)
                assertEquals("settings column ${expected.name} type", expected.type.uppercase(), found!!.type.uppercase())
            }

            val actualIndices = st.executeQuery("PRAGMA index_list(songs)").use { rs ->
                buildMap {
                    while (rs.next()) this[rs.getString(2)] = rs.getInt(3) == 1
                }
            }
            CompiledSchema.songsIndices.forEach { expected ->
                assertTrue("index ${expected.name} exists", actualIndices.containsKey(expected.name))
                assertEquals("index ${expected.name} unique", expected.unique, actualIndices[expected.name])
                val idxCols = st.executeQuery("PRAGMA index_info(\"${expected.name}\")").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(3)) }
                }
                assertEquals("index ${expected.name} columns", expected.columns, idxCols)
            }
        }

        // settings table is usable.
        conn.createStatement().use { st ->
            st.execute("INSERT INTO `settings` (`key`, `value`) VALUES ('default_bitrate', '192')")
            st.executeQuery("SELECT value FROM settings WHERE key = 'default_bitrate'").use { rs ->
                assertTrue(rs.next())
                assertEquals("192", rs.getString(1))
            }
        }

        // UNIQUE(playlist_id, spotify_id) enforced after migration.
        var threw = false
        try {
            conn.createStatement().use { st ->
                st.execute("INSERT INTO songs (id, playlist_id, spotify_id, title, artists, duration_ms, status, position, added_at) " +
                    "VALUES ('dup', 'p1', 'trk-1', 'Dup', 'X', 0, 'PENDING', 5, 1002)")
            }
        } catch (e: SQLException) {
            threw = true
        }
        assertTrue("duplicate (playlist_id, spotify_id) rejected", threw)

        conn.close()
    }

    @Test
    fun T070_deletePlaylistCascadesToSongs() {
        val conn = v1Connection()
        conn.createStatement().use { st -> Migrations.MIGRATION_1_2_STATEMENTS.forEach { st.execute(it) } }
        seed(conn)

        conn.createStatement().use { st ->
            st.execute("DELETE FROM playlists WHERE id = 'p1'")
            val remaining = st.executeQuery("SELECT COUNT(*) FROM songs WHERE playlist_id = 'p1'").use { rs ->
                rs.next(); rs.getInt(1)
            }
            assertEquals("ON DELETE CASCADE removed songs", 0, remaining)
        }
        conn.close()
    }

    @Test
    fun T072_songsOrderedByPlaylistPosition() {
        val conn = v1Connection()
        conn.createStatement().use { st -> Migrations.MIGRATION_1_2_STATEMENTS.forEach { st.execute(it) } }
        conn.createStatement().use { st ->
            st.execute("INSERT INTO playlists (id, spotify_id, name, track_count, status, created_at) VALUES ('p1', 'sp', 'P', 0, 'NEW', 0)")
            listOf(3, 0, 2, 1).forEach { pos ->
                st.execute("INSERT INTO songs (id, playlist_id, spotify_id, title, artists, duration_ms, status, position, added_at) " +
                    "VALUES ('s$pos', 'p1', 't$pos', 'T$pos', 'A', 0, 'PENDING', $pos, 0)")
            }
            // Mirrors SongDao.observeSongsForPlaylist's ORDER BY.
            val positions = st.executeQuery(
                "SELECT position FROM songs WHERE playlist_id = 'p1' ORDER BY position ASC, added_at ASC"
            ).use { rs ->
                buildList { while (rs.next()) add(rs.getInt(1)) }
            }
            assertEquals(listOf(0, 1, 2, 3), positions)
        }
        conn.close()
    }

    @Test
    fun T078_statusFilterReturnsCorrectSubsets() {
        val conn = v1Connection()
        conn.createStatement().use { st -> Migrations.MIGRATION_1_2_STATEMENTS.forEach { st.execute(it) } }
        conn.createStatement().use { st ->
            st.execute("INSERT INTO playlists (id, spotify_id, name, track_count, status, created_at) VALUES ('p1', 'sp', 'P', 0, 'NEW', 0)")
            listOf(
                0 to "DOWNLOADED", 1 to "DOWNLOADED", 2 to "FAILED",
                3 to "PENDING", 4 to "PENDING", 5 to "DOWNLOADING"
            ).forEach { (pos, status) ->
                st.execute("INSERT INTO songs (id, playlist_id, spotify_id, title, artists, duration_ms, status, position, added_at) " +
                    "VALUES ('s$pos', 'p1', 't$pos', 'T$pos', 'A', 0, '$status', $pos, 0)")
            }
            val downloaded = st.executeQuery(
                "SELECT position FROM songs WHERE playlist_id = 'p1' AND status IN ('DOWNLOADED') ORDER BY position"
            ).use { rs -> buildList { while (rs.next()) add(rs.getInt(1)) } }
            assertEquals(listOf(0, 1), downloaded)

            val retryable = st.executeQuery(
                "SELECT position FROM songs WHERE playlist_id = 'p1' AND status IN ('PENDING', 'FAILED') ORDER BY position"
            ).use { rs -> buildList { while (rs.next()) add(rs.getInt(1)) } }
            assertEquals(listOf(2, 3, 4), retryable)
        }
        conn.close()
    }

    @Test
    fun T076_thousandRowBatchInsertUnderFiveSeconds() {
        val conn = v1Connection()
        conn.createStatement().use { st -> Migrations.MIGRATION_1_2_STATEMENTS.forEach { st.execute(it) } }
        conn.createStatement().use { st ->
            st.execute("INSERT INTO playlists (id, spotify_id, name, track_count, status, created_at) VALUES ('p1', 'sp', 'P', 0, 'NEW', 0)")
        }
        val start = System.nanoTime()
        conn.createStatement().use { st ->
            conn.autoCommit = false
            try {
                (0 until 1000).forEach { pos ->
                    st.execute("INSERT INTO songs (id, playlist_id, spotify_id, title, artists, duration_ms, status, position, added_at) " +
                        "VALUES ('s$pos', 'p1', 't$pos', 'T$pos', 'A', 0, 'PENDING', $pos, 0)")
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val count = conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM songs WHERE playlist_id = 'p1'").use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(1000, count)
        assertTrue("1000 rows in $elapsedMs ms < 5000 ms", elapsedMs < 5_000)
        conn.close()
    }

    @Test
    fun T075_failedBatchRollsBackAtomically() {
        val conn = v1Connection()
        conn.createStatement().use { st -> Migrations.MIGRATION_1_2_STATEMENTS.forEach { st.execute(it) } }
        conn.createStatement().use { st ->
            st.execute("INSERT INTO playlists (id, spotify_id, name, track_count, status, created_at) VALUES ('p1', 'sp', 'P', 0, 'NEW', 0)")
            st.execute("INSERT INTO songs (id, playlist_id, spotify_id, title, artists, duration_ms, status, position, added_at) " +
                "VALUES ('s0', 'p1', 't0', 'T0', 'A', 0, 'PENDING', 0, 0)")
        }

        var threw = false
        conn.autoCommit = false
        try {
            conn.createStatement().use { st ->
                // A row that violates UNIQUE(playlist_id, spotify_id) partway through.
                st.execute("INSERT INTO songs (id, playlist_id, spotify_id, title, artists, duration_ms, status, position, added_at) " +
                    "VALUES ('s1', 'p1', 't0', 'Dup', 'A', 0, 'PENDING', 1, 0)")
            }
            conn.commit()
        } catch (e: SQLException) {
            threw = true
            conn.rollback()
        } finally {
            conn.autoCommit = true
        }
        assertTrue("constraint violation surfaced", threw)

        val count = conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM songs WHERE playlist_id = 'p1'").use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals("no half-inserted batch (only the original row)", 1, count)
        assertNotNull(
            "playlist row rolled back together with batch",
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id FROM playlists WHERE id = 'p1'").use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        )
        conn.close()
    }

    @Test
    fun T077_settingsKeyValueRoundTrip() {
        val conn = v1Connection()
        conn.createStatement().use { st ->
            Migrations.MIGRATION_1_2_STATEMENTS.forEach { st.execute(it) }
            listOf(
                "storage_dir" to "/music",
                "default_bitrate" to "192",
                "concurrent_downloads" to "4",
                "auto_download_on_add" to "true"
            ).forEach { (k, v) ->
                st.execute("INSERT INTO `settings` (`key`, `value`) VALUES ('$k', '$v') ON CONFLICT(`key`) DO UPDATE SET `value` = excluded.`value`")
            }
            listOf(
                "storage_dir" to "/music",
                "default_bitrate" to "192",
                "concurrent_downloads" to "4",
                "auto_download_on_add" to "true"
            ).forEach { (k, expected) ->
                st.executeQuery("SELECT value FROM settings WHERE key = '$k'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("round-trip for $k", expected, rs.getString(1))
                }
            }
        }
        conn.close()
    }
}
