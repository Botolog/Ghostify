package com.ghostify.data

import com.ghostify.data.repo.PlaylistRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-076: a large playlist (1000 tracks) batch-inserts in a single transaction
 * well under the 5 s budget.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BulkInsertPerfTest : BaseDbTest() {

    @Test
    fun thousandSongBatchInsertUnderFiveSeconds() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        playlistRepo.savePlaylist(TestData.playlist("p1"))

        val songs = TestData.songs("p1", 1000)

        val start = System.nanoTime()
        playlistRepo.savePlaylistWithSongs(TestData.playlist("p1"), songs)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals("all 1000 tracks persisted", 1000, playlistRepo.getSongs("p1").size)
        assertEquals("track_count derived from batch", 1000, playlistRepo.getPlaylist("p1")?.trackCount)
        assertTrue("1000 rows in $elapsedMs ms < 5000 ms budget", elapsedMs < 5_000)
    }
}
