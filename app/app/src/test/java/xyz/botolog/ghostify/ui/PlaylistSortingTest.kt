package xyz.botolog.ghostify.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.botolog.ghostify.ui.model.SongStatus
import xyz.botolog.ghostify.ui.model.TrackUi
import xyz.botolog.ghostify.ui.playlist.PlaylistSortOption
import xyz.botolog.ghostify.ui.playlist.PlaylistSortSpec
import xyz.botolog.ghostify.ui.playlist.sortPlaylistTracks

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

    private fun track(
        id: String,
        title: String = "track",
        durationMs: Long = 0,
        position: Int = 0,
        addedAt: Long? = null,
        status: SongStatus = SongStatus.PENDING,
    ) = TrackUi(
        id = id,
        spotifyId = "spotify-$id",
        title = title,
        artists = "artist",
        album = "album",
        durationMs = durationMs,
        status = status,
        position = position,
        addedAt = addedAt,
    )
}
