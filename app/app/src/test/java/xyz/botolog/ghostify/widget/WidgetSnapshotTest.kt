package xyz.botolog.ghostify.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.player.core.CurrentItem
import xyz.botolog.ghostify.player.core.PlayerUiState

/**
 * JVM tests for the widget's pure logic only.
 *
 * Glance UI is intentionally not exercised here: [WidgetSnapshot],
 * [snapshotFromPlayerState] and [formatTime] carry no Android types, so they can be
 * verified without an emulator.
 */
class WidgetSnapshotTest {

    private fun state(
        mediaId: String? = "song-1",
        title: String? = "Title",
        artist: String? = "Artist",
        album: String? = "Album",
        isPlaying: Boolean = true,
        positionMs: Long = 1_000L,
        durationMs: Long = 60_000L,
        hasQueue: Boolean = true,
    ) = PlayerUiState(
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        currentItem = CurrentItem(
            mediaId = mediaId,
            title = title,
            artist = artist,
            album = album,
            artworkBytes = null,
        ),
        hasQueue = hasQueue,
    )

    // ── snapshotFromPlayerState ──────────────────────────────────────────

    @Test
    fun snapshotMapsCurrentItemAndPlaybackState() {
        val snapshot = snapshotFromPlayerState(state())

        assertEquals("song-1", snapshot.songId)
        assertEquals("Title", snapshot.title)
        assertEquals("Artist", snapshot.artist)
        assertEquals("Album", snapshot.album)
        assertTrue(snapshot.isPlaying)
        assertEquals(1_000L, snapshot.positionMs)
        assertEquals(60_000L, snapshot.durationMs)
        assertTrue(snapshot.hasQueue)
        assertFalse(snapshot.isEmpty)
    }

    @Test
    fun snapshotIsEmptyWhenNothingIsLoaded() {
        val snapshot = snapshotFromPlayerState(PlayerUiState())
        assertEquals(WidgetSnapshot.EMPTY, snapshot)
        assertTrue(snapshot.isEmpty)
        assertNull(snapshot.songId)
    }

    @Test
    fun snapshotIsEmptyWhenMediaIdIsBlank() {
        assertTrue(snapshotFromPlayerState(state(mediaId = "")).isEmpty)
        assertTrue(snapshotFromPlayerState(state(mediaId = "   ")).isEmpty)
    }

    @Test
    fun snapshotToleratesNullMetadata() {
        val snapshot = snapshotFromPlayerState(state(title = null, artist = null, album = null))
        assertEquals("", snapshot.title)
        assertEquals("", snapshot.artist)
        assertNull(snapshot.album)
    }

    @Test
    fun snapshotCarriesArtworkLyricsAndActiveLine() {
        val bytes = byteArrayOf(1, 2, 3)
        val snapshot = snapshotFromPlayerState(
            state = state(),
            coverBytes = bytes,
            lyricsLines = listOf("one", "two", "three"),
            currentLineIndex = 1,
        )
        assertEquals(3, snapshot.coverBytes?.size)
        assertEquals(listOf("one", "two", "three"), snapshot.lyricsLines)
        assertEquals(1, snapshot.currentLineIndex)
    }

    // ── formatTime ───────────────────────────────────────────────────────

    @Test
    fun formatTimeFormatsMinutesAndZeroPadsSeconds() {
        assertEquals("0:00", formatTime(0L))
        assertEquals("0:05", formatTime(5_000L))
        assertEquals("0:59", formatTime(59_999L))
        assertEquals("1:00", formatTime(60_000L))
        assertEquals("1:05", formatTime(65_000L))
        assertEquals("59:59", formatTime(3_599_000L))
    }

    @Test
    fun formatTimeClampsNegativeToZero() {
        assertEquals("0:00", formatTime(-1L))
        assertEquals("0:00", formatTime(-60_000L))
    }

    @Test
    fun formatTimeRollsHoursIntoMinutes() {
        assertEquals("60:00", formatTime(3_600_000L))
        assertEquals("61:01", formatTime(3_661_000L))
    }

    // ── playerSecondaryLine (wide player) ────────────────────────────────

    @Test
    fun secondaryLineJoinsArtistAndAlbum() {
        assertEquals("Neon Tides · Static Bloom", playerSecondaryLine("Neon Tides", "Static Bloom"))
    }

    @Test
    fun secondaryLineCollapsesMissingParts() {
        // No dangling separator when the album (or the artist) is unknown.
        assertEquals("Neon Tides", playerSecondaryLine("Neon Tides", null))
        assertEquals("Neon Tides", playerSecondaryLine("Neon Tides", ""))
        assertEquals("Neon Tides", playerSecondaryLine("Neon Tides", "   "))
        assertEquals("Static Bloom", playerSecondaryLine("", "Static Bloom"))
        assertEquals("", playerSecondaryLine("", null))
    }

    @Test
    fun secondaryLineTrimsSurroundingWhitespace() {
        assertEquals("A · B", playerSecondaryLine("  A ", " B  "))
    }

    // ── progressFraction ─────────────────────────────────────────────────

    @Test
    fun progressFractionIsZeroForUnknownDuration() {
        assertEquals(0f, snapshotFromPlayerState(state(durationMs = 0L)).progressFraction(), 0f)
        assertEquals(0f, snapshotFromPlayerState(state(durationMs = -5L)).progressFraction(), 0f)
    }

    @Test
    fun progressFractionIsPositionOverDurationClamped() {
        assertEquals(0.5f, snapshotFromPlayerState(state(positionMs = 30_000L, durationMs = 60_000L)).progressFraction(), 0.0001f)
        // A stale position past the duration must not exceed 1.
        assertEquals(1f, snapshotFromPlayerState(state(positionMs = 90_000L, durationMs = 60_000L)).progressFraction(), 0f)
    }

    // ── updateKey / immediateKey (the distinct-until-changed logic) ──────

    @Test
    fun updateKeyIgnoresSubSecondPositionChanges() {
        val a = snapshotFromPlayerState(state(positionMs = 1_000L))
        val b = snapshotFromPlayerState(state(positionMs = 1_900L))
        assertEquals(a.updateKey(), b.updateKey())
    }

    @Test
    fun updateKeyChangesOnWholeSecondBoundary() {
        val a = snapshotFromPlayerState(state(positionMs = 1_900L))
        val b = snapshotFromPlayerState(state(positionMs = 2_000L))
        assertNotEquals(a.updateKey(), b.updateKey())
    }

    @Test
    fun updateKeyChangesWhenVisibleFieldChanges() {
        val base = snapshotFromPlayerState(state())

        assertNotEquals(base.updateKey(), snapshotFromPlayerState(state(title = "Other")).updateKey())
        assertNotEquals(base.updateKey(), snapshotFromPlayerState(state(artist = "Other")).updateKey())
        assertNotEquals(base.updateKey(), snapshotFromPlayerState(state(isPlaying = false)).updateKey())
        assertNotEquals(base.updateKey(), snapshotFromPlayerState(state(hasQueue = false)).updateKey())
        assertNotEquals(base.updateKey(), snapshotFromPlayerState(state(mediaId = "song-2")).updateKey())
        assertNotEquals(base.updateKey(), snapshotFromPlayerState(state(), currentLineIndex = 2).updateKey())
        assertNotEquals(
            base.updateKey(),
            snapshotFromPlayerState(state(), coverBytes = byteArrayOf(1)).updateKey(),
        )
    }

    @Test
    fun immediateKeyTracksTrackAndPlayStateOnly() {
        val playing = snapshotFromPlayerState(state(isPlaying = true))
        val paused = snapshotFromPlayerState(state(isPlaying = false))
        val otherTrack = snapshotFromPlayerState(state(mediaId = "song-2"))
        val movedPosition = snapshotFromPlayerState(state(positionMs = 55_000L))

        assertEquals(playing.immediateKey(), movedPosition.immediateKey())
        assertNotEquals(playing.immediateKey(), paused.immediateKey())
        assertNotEquals(playing.immediateKey(), otherTrack.immediateKey())
    }

    // ── empty state ──────────────────────────────────────────────────────

    @Test
    fun emptySnapshotHasNeutralDefaults() {
        val empty = WidgetSnapshot.EMPTY
        assertTrue(empty.isEmpty)
        assertNull(empty.songId)
        assertEquals("", empty.title)
        assertEquals("", empty.artist)
        assertNull(empty.album)
        assertFalse(empty.isPlaying)
        assertFalse(empty.hasQueue)
        assertEquals(0L, empty.positionMs)
        assertEquals(0L, empty.durationMs)
        assertNull(empty.coverBytes)
        assertTrue(empty.lyricsLines.isEmpty())
        assertEquals(-1, empty.currentLineIndex)
        assertEquals(0f, empty.progressFraction(), 0f)
    }

    @Test
    fun emptyAndPlayingSnapshotsHaveDistinctKeys() {
        val empty = WidgetSnapshot.EMPTY
        val playing = snapshotFromPlayerState(state())
        assertNotEquals(empty.updateKey(), playing.updateKey())
        assertNotEquals(empty.immediateKey(), playing.immediateKey())
    }
}
