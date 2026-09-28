package xyz.botolog.ghostify.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.ui.playlist.PlaylistSortOption
import xyz.botolog.ghostify.ui.playlist.isStoredOrder
import xyz.botolog.ghostify.ui.playlist.storedSortSpec
import java.io.File

/**
 * The playlist's saved sort is two columns on `playlists`, added in v9.
 *
 * The upgrade has to be safe for playlists that already exist: both columns are `NOT NULL`
 * with a backfilling default, so every one of them keeps the order its `songs.position`
 * values already describe and none is re-sorted by the upgrade. The exported schema is
 * checked alongside the SQL because Room validates an upgraded database against it at
 * runtime — a default that only looks right in the `ALTER` would fail on first launch.
 */
class PlaylistSortSchemaMigrationTest {

    @Test
    fun theUpgradeAddsBothSortColumnsToPlaylists() {
        assertEquals(
            listOf(
                "ALTER TABLE playlists ADD COLUMN sort_field TEXT NOT NULL DEFAULT 'playlist_order'",
                "ALTER TABLE playlists ADD COLUMN sort_descending INTEGER NOT NULL DEFAULT 0",
            ),
            Migrations.MIGRATION_8_9_STATEMENTS,
        )
    }

    @Test
    fun theUpgradeOnlyTouchesThePlaylistTable() {
        Migrations.MIGRATION_8_9_STATEMENTS.forEach { statement ->
            assertTrue(
                "not a playlists upgrade: $statement",
                statement.contains("ALTER TABLE playlists"),
            )
        }
    }

    @Test
    fun theUpgradeRunsAsOneOfTheRegisteredStepsInOrder() {
        val versions = Migrations.ALL.map { it.startVersion to it.endVersion }

        assertEquals((1 until DATABASE_VERSION).map { it to it + 1 }, versions)
        assertEquals(DATABASE_VERSION, Migrations.ALL.last().endVersion)
    }

    @Test
    fun anExistingPlaylistKeepsItsOwnOrderAscending() {
        val upgraded = storedSortSpec(
            sortField = SORT_FIELD_DEFAULT,
            sortDescending = SORT_DESCENDING_DEFAULT,
        )

        assertEquals(PlaylistSortOption.PLAYLIST_ORDER, upgraded.option)
        assertFalse(upgraded.descending)
        assertTrue("the upgrade must not need a re-sort", upgraded.isStoredOrder())
    }

    @Test
    fun theEntityDefaultsAreTheBackfillValues() {
        val playlist = PlaylistEntity(id = "p", spotifyId = "s", name = "Playlist")

        assertEquals(PlaylistEntity.SORT_FIELD_PLAYLIST_ORDER, playlist.sortField)
        assertFalse(playlist.sortDescending)
    }

    @Test
    fun theEntityDefaultTokenIsTheSortOptionTheDatabaseStores() {
        assertEquals(
            PlaylistSortOption.PLAYLIST_ORDER.storageValue,
            PlaylistEntity.SORT_FIELD_PLAYLIST_ORDER,
        )
    }

    @Test
    fun theExportedSchemaCarriesTheSortColumnsAndTheirDefaults() {
        val schema = exportedSchema()

        assertTrue(
            "sort_field missing from the playlists table",
            schema.contains("`sort_field` TEXT NOT NULL DEFAULT 'playlist_order'"),
        )
        assertTrue(
            "sort_descending missing from the playlists table",
            schema.contains("`sort_descending` INTEGER NOT NULL DEFAULT 0"),
        )
    }

    @Test
    fun theExportedSchemaIsTheVersionTheMigrationEndsAt() {
        val schema = exportedSchema()

        assertTrue(
            "the exported schema is not v$DATABASE_VERSION",
            schema.contains("\"version\": $DATABASE_VERSION"),
        )
    }

    private fun exportedSchema(): String {
        val file = File("schemas/xyz.botolog.ghostify.data.db.AppDatabase/$DATABASE_VERSION.json")
        assertTrue(
            "exported schema not found at ${file.absolutePath} — run the build that generates it",
            file.isFile,
        )
        return file.readText()
    }

    private companion object {
        /** The version `AppDatabase` declares, which v8 → v9 has to reach. */
        const val DATABASE_VERSION = 9
        const val SORT_FIELD_DEFAULT = PlaylistEntity.SORT_FIELD_PLAYLIST_ORDER
        const val SORT_DESCENDING_DEFAULT = false
    }
}
