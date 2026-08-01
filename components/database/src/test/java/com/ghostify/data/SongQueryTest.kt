package com.ghostify.data

import com.ghostify.data.model.SongStatus
import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SongRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-072: `songsFor(playlistId)` is ordered by playlist position.
 * T-078: status-filtered queries return the correct subsets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SongQueryTest : BaseDbTest() {

    @Test
    fun songsOrderedByPosition() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        val songRepo = SongRepository(db.songDao(), db.transactionRunner())

        // Insert in deliberately scrambled order.
        playlistRepo.savePlaylistWithSongs(
            TestData.playlist("p1"),
            listOf(
                TestData.song("p1", 3),
                TestData.song("p1", 0),
                TestData.song("p1", 2),
                TestData.song("p1", 1)
            )
        )

        val ordered = songRepo.getSongs("p1")
        assertEquals("ordered by position", listOf(0, 1, 2, 3), ordered.map { it.position })
    }

    @Test
    fun statusFilterReturnsCorrectSubsets() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        val songRepo = SongRepository(db.songDao(), db.transactionRunner())

        val all = listOf(
            TestData.song("p1", 0, status = SongStatus.DOWNLOADED),
            TestData.song("p1", 1, status = SongStatus.DOWNLOADED),
            TestData.song("p1", 2, status = SongStatus.FAILED),
            TestData.song("p1", 3, status = SongStatus.PENDING),
            TestData.song("p1", 4, status = SongStatus.PENDING),
            TestData.song("p1", 5, status = SongStatus.DOWNLOADING)
        )
        playlistRepo.savePlaylistWithSongs(TestData.playlist("p1"), all)

        val downloaded = songRepo.getSongsByStatus("p1", listOf(SongStatus.DOWNLOADED))
        assertEquals("only DOWNLOADED rows", listOf(0, 1), downloaded.map { it.position })

        val retryable = songRepo.getSongsByStatus("p1", listOf(SongStatus.PENDING, SongStatus.FAILED))
        assertEquals("PENDING + FAILED rows", listOf(2, 3, 4), retryable.map { it.position })

        val none = songRepo.getSongsByStatus("p1", listOf(SongStatus.REMOVED))
        assertEquals("no REMOVED rows", emptyList<Int>(), none.map { it.position })

        val emptyStatuses = songRepo.getSongsByStatus("p1", emptyList())
        assertEquals("empty status list matches nothing", emptyList<Int>(), emptyStatuses.map { it.position })
    }
}
