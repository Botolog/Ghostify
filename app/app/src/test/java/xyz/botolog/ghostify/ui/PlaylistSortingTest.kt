package xyz.botolog.ghostify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.ui.model.SongStatus
import xyz.botolog.ghostify.ui.model.TrackUi
import xyz.botolog.ghostify.ui.playlist.PlaylistSortOption
import xyz.botolog.ghostify.ui.playlist.PlaylistSortSpec
import xyz.botolog.ghostify.ui.playlist.isStoredOrder
import xyz.botolog.ghostify.ui.playlist.orderedBy
import xyz.botolog.ghostify.ui.playlist.sortPlaylistTracks
import xyz.botolog.ghostify.ui.playlist.sortedTrackIds

class PlaylistSortingTest {
    @Test
    fun titleSortIsCaseInsensitiveAndUsesIdForTies() {
        val tracks = listOf(
            track(id = "c", title = "beta"),
            track(id = "b", title = "ALPHA"),
            track(id = "a", title = "alpha"),
        )

        val sorted = sortPlaylistTracks(
            tracks,
            PlaylistSortSpec(option = PlaylistSortOption.TITLE),
        )

        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
    }

    @Test
    fun activeSortIsReappliedToTracksAddedBySync() {
        val activeSort = PlaylistSortSpec(option = PlaylistSortOption.TITLE)
        val beforeSync = listOf(
            track(id = "b", title = "beta", position = 2),
            track(id = "a", title = "alpha", position = 0),
        )
        val afterSync = beforeSync + track(id = "c", title = "gamma", position = 1)

        val sorted = sortPlaylistTracks(afterSync, activeSort)

        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
        assertEquals(listOf(2, 0, 1), afterSync.map { it.position })
    }

    @Test
    fun durationSortUsesNumericDuration() {
        val tracks = listOf(
            track(id = "long", durationMs = 300),
            track(id = "short", durationMs = 100),
            track(id = "medium", durationMs = 200),
        )

        val sorted = sortPlaylistTracks(
            tracks,
            PlaylistSortSpec(option = PlaylistSortOption.DURATION),
        )

        assertEquals(listOf("short", "medium", "long"), sorted.map { it.id })
    }

    @Test
    fun dateAddedSortUsesNumericDate() {
        val tracks = listOf(
            track(id = "later", addedAt = 200),
            track(id = "earlier", addedAt = 100),
            track(id = "latest", addedAt = 300),
        )

        val sorted = sortPlaylistTracks(
            tracks,
            PlaylistSortSpec(option = PlaylistSortOption.DATE_ADDED),
        )

        assertEquals(listOf("earlier", "later", "latest"), sorted.map { it.id })
    }

    @Test
    fun descendingReversesPrimarySortButKeepsIdTieBreakersAscending() {
        val tracks = listOf(
            track(id = "b", title = "same"),
            track(id = "a", title = "same"),
            track(id = "z", title = "zeta"),
            track(id = "a2", title = "alpha"),
        )

        val sorted = sortPlaylistTracks(
            tracks,
            PlaylistSortSpec(option = PlaylistSortOption.TITLE, descending = true),
        )

        assertEquals(listOf("z", "a", "b", "a2"), sorted.map { it.id })
    }

    @Test
    fun playlistOrderSortUsesPosition() {
        val tracks = listOf(
            track(id = "third", position = 2),
            track(id = "first", position = 0),
            track(id = "second", position = 1),
        )

        val sorted = sortPlaylistTracks(
            tracks,
            PlaylistSortSpec(option = PlaylistSortOption.PLAYLIST_ORDER),
        )

        assertEquals(listOf("first", "second", "third"), sorted.map { it.id })
    }

    @Test
    fun downloadedStatusSortGroupsDownloadedTracks() {
        val tracks = listOf(
            track(id = "downloaded", status = SongStatus.DOWNLOADED),
            track(id = "pending", status = SongStatus.PENDING),
            track(id = "failed", status = SongStatus.FAILED),
        )

        val sorted = sortPlaylistTracks(
            tracks,
            PlaylistSortSpec(option = PlaylistSortOption.DOWNLOADED_STATUS),
        )

        assertEquals(listOf("failed", "pending", "downloaded"), sorted.map { it.id })
    }

    @Test
    fun sortedTrackIdsResolvesTheOrderToPersist() {
        val tracks = listOf(
            track(id = "c", title = "Charlie", position = 0),
            track(id = "b", title = "Bravo", position = 1),
            track(id = "a", title = "Alpha", position = 2),
        )

        assertEquals(
            listOf("a", "b", "c"),
            sortedTrackIds(tracks, PlaylistSortSpec(option = PlaylistSortOption.TITLE)),
        )
    }

    @Test
    fun sortedTrackIdsIsStableAcrossRepeatedRuns() {
        val tracks = listOf(
            track(id = "b", title = "same", position = 0),
            track(id = "a", title = "same", position = 1),
        )
        val spec = PlaylistSortSpec(option = PlaylistSortOption.TITLE)

        assertEquals(sortedTrackIds(tracks, spec), sortedTrackIds(tracks, spec))
    }

    @Test
    fun storedOrderSpecNeedsNoWrite() {
        assertTrue(PlaylistSortSpec().isStoredOrder())
        assertTrue(PlaylistSortSpec(option = PlaylistSortOption.PLAYLIST_ORDER).isStoredOrder())
        assertFalse(PlaylistSortSpec(option = PlaylistSortOption.PLAYLIST_ORDER, descending = true).isStoredOrder())
        assertFalse(PlaylistSortSpec(option = PlaylistSortOption.TITLE).isStoredOrder())
    }

    @Test
    fun orderedByRenumbersPositionsAndKeepsUnknownTracks() {
        val tracks = listOf(
            track(id = "c", title = "Charlie", position = 0),
            track(id = "b", title = "Bravo", position = 1),
            track(id = "a", title = "Alpha", position = 2),
        )

        val reordered = tracks.orderedBy(listOf("a", "b"))

        assertEquals(listOf("a", "b", "c"), reordered.map { it.id })
        assertEquals(listOf(0, 1, 2), reordered.map { it.position })
    }

    @Test
    fun artistAlbumTitleSortOrdersByArtistBeforeAlbumAndTitle() {
        val tracks = listOf(
            track(id = "z", artists = "Beta", album = "A", title = "A"),
            track(id = "y", artists = "alpha", album = "Z", title = "A"),
            track(id = "x", artists = "ALPHA", album = "A", title = "Z"),
            track(id = "w", artists = "Beta", album = "A", title = "A"),
        )

        val sorted = sortPlaylistTracks(
            tracks,
            PlaylistSortSpec(option = PlaylistSortOption.ARTIST_ALBUM_TITLE),
        )

        assertEquals(listOf("x", "y", "w", "z"), sorted.map { it.id })
    }

    @Test
    fun artistAlbumTitleSortGroupsAlbumsAndTracksWithinAnArtist() {
        val tracks = listOf(
            track(id = "3", artists = "Beta", album = "Two", title = "gamma"),
            track(id = "4", artists = "Beta", album = "Two", title = "Alpha"),
            track(id = "2", artists = "Beta", album = "One", title = "zeta"),
            track(id = "1", artists = "Alpha", album = "Nine", title = "zeta"),
        )

        val sorted = sortPlaylistTracks(
            tracks,
            PlaylistSortSpec(option = PlaylistSortOption.ARTIST_ALBUM_TITLE),
        )

        assertEquals(listOf("1", "2", "4", "3"), sorted.map { it.id })
    }

    @Test
    fun artistAlbumTitleDescendingReversesEveryKeyButKeepsIdTiesAscending() {
        val tracks = listOf(
            track(id = "1", artists = "Alpha", album = "One", title = "beta"),
            track(id = "2", artists = "Alpha", album = "One", title = "alpha"),
            track(id = "3", artists = "Alpha", album = "One", title = "alpha"),
            track(id = "4", artists = "Alpha", album = "Zero", title = "zeta"),
        )

        val sorted = sortPlaylistTracks(
            tracks,
            PlaylistSortSpec(option = PlaylistSortOption.ARTIST_ALBUM_TITLE, descending = true),
        )

        assertEquals(listOf("4", "1", "2", "3"), sorted.map { it.id })
    }

    @Test
    fun artistAlbumTitleSortGroupsBlankMetadataLikeTheSingleFieldOptions() {
        val tracks = listOf(
            track(id = "named", artists = "Alpha", album = "One", title = "alpha"),
            track(id = "blankAlbum", artists = "Alpha", album = "", title = "zeta"),
            track(id = "blankArtist", artists = "", album = "One", title = "zeta"),
        )
        val spec = PlaylistSortSpec(option = PlaylistSortOption.ARTIST_ALBUM_TITLE)

        val sorted = sortPlaylistTracks(tracks, spec)

        assertEquals(listOf("blankArtist", "blankAlbum", "named"), sorted.map { it.id })
        assertEquals(
            sortPlaylistTracks(tracks, PlaylistSortSpec(option = PlaylistSortOption.ARTIST))
                .map { it.artists }
                .distinct(),
            sorted.map { it.artists }.distinct(),
        )
    }

    @Test
    fun artistAlbumTitleSortIsStableForIdenticalKeys() {
        val tracks = listOf(
            track(id = "c", artists = "Alpha", album = "One", title = "Same"),
            track(id = "b", artists = "ALPHA", album = "one", title = "same"),
            track(id = "a", artists = "Alpha", album = "One", title = "Same"),
        )
        val spec = PlaylistSortSpec(option = PlaylistSortOption.ARTIST_ALBUM_TITLE)

        val sorted = sortPlaylistTracks(tracks, spec)

        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
        assertEquals(sorted.map { it.id }, sortPlaylistTracks(tracks, spec).map { it.id })
    }

    @Test
    fun sortedTrackIdsResolvesTheArtistAlbumTitleOrderToPersist() {
        val tracks = listOf(
            track(id = "a", artists = "Beta", album = "One", title = "alpha", position = 0),
            track(id = "b", artists = "Alpha", album = "Two", title = "alpha", position = 1),
            track(id = "c", artists = "Alpha", album = "One", title = "alpha", position = 2),
        )

        assertEquals(
            listOf("c", "b", "a"),
            sortedTrackIds(tracks, PlaylistSortSpec(option = PlaylistSortOption.ARTIST_ALBUM_TITLE)),
        )
    }

    @Test
    fun sortOptionStorageValuesRoundTrip() {
        PlaylistSortOption.entries.forEach { option ->
            assertEquals(option, PlaylistSortOption.fromStorageValue(option.storageValue))
        }
        assertEquals(
            PlaylistSortOption.ARTIST_ALBUM_TITLE,
            PlaylistSortOption.fromStorageValue("  ARTIST_ALBUM_TITLE  "),
        )
        assertEquals(
            PlaylistSortOption.entries.size,
            PlaylistSortOption.entries.map { it.storageValue }.distinct().size,
        )
    }

    @Test
    fun unknownSortOptionStorageValueResolvesToNothing() {
        assertNull(PlaylistSortOption.fromStorageValue(null))
        assertNull(PlaylistSortOption.fromStorageValue(""))
        assertNull(PlaylistSortOption.fromStorageValue("artist_album_song"))
    }

    private fun track(
        id: String,
        title: String = "track",
        artists: String = "artist",
        album: String = "album",
        durationMs: Long = 0,
        position: Int = 0,
        addedAt: Long? = null,
        status: SongStatus = SongStatus.PENDING,
    ) = TrackUi(
        id = id,
        spotifyId = "spotify-$id",
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
        status = status,
        position = position,
        addedAt = addedAt,
    )
}
