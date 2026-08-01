package com.ghostify.download

import kotlin.test.Test
import kotlin.test.assertEquals

/** T-042 / T-043 / T-045: selection logic of `downloadAll`. */
class DownloadSelectorTest {

    private fun song(id: String, pos: Int, status: DownloadStatus) = SongRecord(
        id = id,
        playlistId = "p1",
        spotifyId = "spot-$id",
        title = "Title $id",
        artists = "Artist",
        position = pos,
        status = status,
    )

    @Test
    fun `picks up only PENDING and FAILED songs`() {
        val songs = listOf(
            song("a", 0, DownloadStatus.PENDING),
            song("b", 1, DownloadStatus.FAILED),
            song("c", 2, DownloadStatus.DOWNLOADED),
            song("d", 3, DownloadStatus.QUEUED),
            song("e", 4, DownloadStatus.DOWNLOADING),
            song("f", 5, DownloadStatus.CANCELED),
        )
        assertEquals(listOf("a", "b"), DownloadSelector.select(songs).map { it.id })
    }

    @Test
    fun `DOWNLOADED songs are never re-enqueued`() {
        val songs = listOf(
            song("a", 0, DownloadStatus.DOWNLOADED),
            song("b", 1, DownloadStatus.DOWNLOADED),
            song("c", 2, DownloadStatus.PENDING),
        )
        val selected = DownloadSelector.select(songs)
        assertEquals(listOf("c"), selected.map { it.id })
    }

    @Test
    fun `order respects playlist track order`() {
        val songs = listOf(
            song("pos5", 5, DownloadStatus.FAILED),
            song("pos0", 0, DownloadStatus.PENDING),
            song("pos9", 9, DownloadStatus.PENDING),
            song("pos2", 2, DownloadStatus.FAILED),
        )
        assertEquals(listOf("pos0", "pos2", "pos5", "pos9"), DownloadSelector.select(songs).map { it.id })
    }

    @Test
    fun `empty selection when nothing is downloadable`() {
        val songs = listOf(
            song("a", 0, DownloadStatus.DOWNLOADED),
            song("b", 1, DownloadStatus.QUEUED),
        )
        assertEquals(0, DownloadSelector.select(songs).size)
    }
}
