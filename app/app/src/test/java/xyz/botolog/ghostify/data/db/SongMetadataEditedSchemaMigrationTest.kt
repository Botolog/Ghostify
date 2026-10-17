package xyz.botolog.ghostify.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.data.db.entity.SongEntity
import java.io.File

/**
 * The user-edit provenance flag for display metadata is a column on `songs`, added in v10.
 *
 * The upgrade has to be safe for songs that already exist: the column is `NOT NULL` with a
 * backfilling default of `0`, so every one of them is treated as "not user-edited" and a
 * re-sync keeps deriving its title/artists/album from the remote until the user saves an edit.
 * The exported schema is checked alongside the SQL because Room validates an upgraded
 * database against it at runtime — a default that only looks right in the `ALTER` would fail
 * on first launch.
 */
class SongMetadataEditedSchemaMigrationTest {

    @Test
    fun theUpgradeAddsTheMetadataEditedColumnToSongs() {
        assertEquals(
            listOf(
                "ALTER TABLE songs ADD COLUMN metadata_edited INTEGER NOT NULL DEFAULT 0",
            ),
            Migrations.MIGRATION_9_10_STATEMENTS,
        )
    }

    @Test
    fun theUpgradeOnlyTouchesTheSongTable() {
        Migrations.MIGRATION_9_10_STATEMENTS.forEach { statement ->
            assertTrue(
                "not a songs upgrade: $statement",
                statement.contains("ALTER TABLE songs"),
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
    fun theEntityDefaultIsNotUserEdited() {
        val song = SongEntity(
            id = "s1",
            playlistId = "p1",
            spotifyId = "sp1",
            title = "Title",
            artists = "Artist",
        )

        assertFalse(song.metadataEdited)
    }

    @Test
    fun theExportedSchemaCarriesTheMetadataEditedColumnAndItsDefault() {
        val schema = exportedSchema()

        assertTrue(
            "metadata_edited missing from the songs table",
            schema.contains("`metadata_edited` INTEGER NOT NULL DEFAULT 0"),
        )
        assertTrue(
            "metadata_edited column missing from the exported schema",
            schema.contains("\"columnName\": \"metadata_edited\""),
        )
        assertTrue(
            "metadata_edited default is not 0",
            schema.contains("\"defaultValue\": \"0\""),
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
        /** The version `AppDatabase` declares, which the migration chain has to reach. */
        const val DATABASE_VERSION = 10
    }
}
