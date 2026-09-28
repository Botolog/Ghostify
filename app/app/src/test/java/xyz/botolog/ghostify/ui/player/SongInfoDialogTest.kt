package xyz.botolog.ghostify.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.ui.contract.PlayerContract.SongMetadataUpdateResult

/**
 * The song-info dialog is a fixed, scrollable box that only ever writes when Save is
 * pressed: the height is a property of the window (never of the song, so it cannot jump
 * between tracks), the edited values are normalised before they reach the database, and a
 * failed save keeps the dialog (and the edit) on screen.
 */
class SongInfoDialogTest {

    // ── Fixed size ───────────────────────────────────────────────────────────

    @Test
    fun theBodyTakesHalfTheWindow() {
        assertEquals(400.dp, songInfoDialogBodyHeight(800f))
        assertEquals(600f * SONG_INFO_BODY_HEIGHT_FRACTION, songInfoDialogBodyHeight(600f).value, 0.01f)
    }

    @Test
    fun theBodyNeverCollapsesOnASmallPhone() {
        assertEquals(200.dp, songInfoDialogBodyHeight(300f))
        assertEquals(200.dp, songInfoDialogBodyHeight(1f))
    }

    @Test
    fun theBodyNeverGrowsIntoAFullSheetOnATablet() {
        assertEquals(420.dp, songInfoDialogBodyHeight(2_000f))
        assertEquals(420.dp, songInfoDialogBodyHeight(Float.MAX_VALUE / 2f))
    }

    @Test
    fun anUnmeasuredWindowFallsBackToTheBoundedMaximum() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { screenHeightDp ->
            assertEquals(
                "screen height $screenHeightDp",
                420.dp,
                songInfoDialogBodyHeight(screenHeightDp),
            )
        }
    }

    @Test
    fun theBodyOnlyEverGrowsWithTheWindow() {
        var previous = 0f

        var screenHeightDp = 200f
        while (screenHeightDp <= 1_600f) {
            val height = songInfoDialogBodyHeight(screenHeightDp).value

            assertTrue("height shrank at $screenHeightDp", height >= previous)
            assertTrue("height out of bounds at $screenHeightDp: $height", height in 200f..420f)
            previous = height
            screenHeightDp += 25f
        }
    }

    @Test
    fun theBodyIsTheSameBoxForEverySongInTheSameWindow() {
        // The height is derived from the window alone, so re-opening the dialog on a
        // different track — with a different number of detail rows — cannot resize it.
        val perSong = (0..40).map { songInfoDialogBodyHeight(760f) }

        assertEquals(1, perSong.distinct().size)
    }

    // ── Save mapping ────────────────────────────────────────────────────────

    @Test
    fun saveTrimsTheEditedFields() {
        val edit = songMetadataEdit(
            title = "  Renamed Track  ",
            artists = "  Ada, Mike  ",
            album = "  Back Catalogue  ",
        )

        assertEquals("Renamed Track", edit.title)
        assertEquals("Ada, Mike", edit.artists)
        assertEquals("Back Catalogue", edit.album)
    }

    @Test
    fun aBlankAlbumIsStoredAsNoAlbum() {
        assertNull(songMetadataEdit("Title", "Artist", "   ").albumOrNull)
        assertEquals("One", songMetadataEdit("Title", "Artist", " One ").albumOrNull)
    }

    @Test
    fun aSongWithoutATitleCannotBeSaved() {
        assertFalse(songMetadataEdit("   ", "Artist", "Album").isSavable)
        assertTrue(songMetadataEdit("Title", "", "").isSavable)
    }

    // ── Save vs discard ─────────────────────────────────────────────────────

    @Test
    fun aDiscardedEditIsNeverMistakenForASavedOne() {
        // Discard leaves the database untouched and reports nothing at all, so a reported
        // outcome is the only thing that can close the dialog.
        val saved = songInfoSaveError(SongMetadataUpdateResult.Updated)
        val notSaved = songInfoSaveError(SongMetadataUpdateResult.Failed("Couldn't save the changes."))

        assertNull(saved)
        assertEquals("Couldn't save the changes.", notSaved)
    }

    @Test
    fun aRetryAfterAFailedSaveSendsTheSameEdit() {
        val beforeFailure = songMetadataEdit("Renamed", "Ada", "")

        songInfoSaveError(SongMetadataUpdateResult.Failed("Couldn't save the changes."))
        val retried = songMetadataEdit(beforeFailure.title, beforeFailure.artists, beforeFailure.album)

        assertEquals(beforeFailure, retried)
        assertTrue(retried.isSavable)
    }

    // ── Outcome mapping ─────────────────────────────────────────────────────

    @Test
    fun aFailedSaveKeepsTheDialogOpenWithItsReason() {
        assertEquals(
            "This song is gone",
            songInfoSaveError(SongMetadataUpdateResult.Failed("This song is gone")),
        )
    }

    @Test
    fun aSuccessfulSaveClosesTheDialog() {
        assertNull(songInfoSaveError(SongMetadataUpdateResult.Updated))
    }
}
