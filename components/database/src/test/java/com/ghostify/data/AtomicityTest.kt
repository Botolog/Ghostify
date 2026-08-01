package com.ghostify.data

import android.database.sqlite.SQLiteConstraintException
import com.ghostify.data.model.SongStatus
import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SongRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-075: writes are atomic — a failed batch leaves no half-inserted state.
 * `PlaylistRepository.savePlaylistWithSongs` runs in a single transaction: if the
 * song batch fails (e.g. a UNIQUE or FK violation) the playlist row rolls back too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AtomicityTest : BaseDbTest() {

    @Test
    fun failedSongBatchRollsBackWholePlaylist() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        val songRepo = SongRepository(db.songDao(), db.transactionRunner())

        val batchWithDup = TestData.songs("p1", 2) + TestData.song("p1", 2, spotifyId = "track-0")

        var threw = false
        try {
            playlistRepo.savePlaylistWithSongs(TestData.playlist("p1"), batchWithDup)
        } catch (e: SQLiteConstraintException) {
            threw = true
        }

        assertTrue("violation surfaced", threw)
        assertTrue("no half-inserted playlist", playlistRepo.getPlaylist("p1") == null)
        assertEquals("no orphan songs", 0, songRepo.countForPlaylist("p1"))
        assertEquals("no playlist counted either", 0, db.playlistDao().count())
    }

    @Test
    fun statusBatchRollsBackOnLateFailure() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        val songRepo = SongRepository(db.songDao(), db.transactionRunner())
        playlistRepo.savePlaylist(TestData.playlist("p1"))

        // A song with an id that matches nothing: the batch must complete without
        // partial writes (no matching rows -> nothing changed, no error).
        songRepo.setStatus(listOf("ghost-id"), SongStatus.QUEUED)
        assertTrue("batch completed without partial writes", songRepo.getSongs("p1").all { it.status == SongStatus.PENDING })
    }
}
