package com.ghostify.data

import android.database.sqlite.SQLiteConstraintException
import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SongRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-070: `songs` FK -> playlists; deleting a playlist cascades to its songs.
 * T-071: `UNIQUE(playlist_id, spotify_id)` enforced — duplicate insert rejected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SongConstraintTest : BaseDbTest() {

    @Test
    fun deletePlaylistCascadesToSongs() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        val songRepo = SongRepository(db.songDao(), db.transactionRunner())

        playlistRepo.savePlaylistWithSongs(TestData.playlist("p1"), TestData.songs("p1", 3))
        assertTrue("songs inserted", songRepo.countForPlaylist("p1") == 3)

        playlistRepo.deletePlaylist("p1")

        assertTrue("cascade removed all songs", songRepo.countForPlaylist("p1") == 0)
        assertTrue("cascade left none behind", songRepo.getSongs("p1").isEmpty())
    }

    @Test
    fun duplicateSpotifyIdInSamePlaylistRejected() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        val songRepo = SongRepository(db.songDao(), db.transactionRunner())

        playlistRepo.savePlaylistWithSongs(
            TestData.playlist("p1"),
            listOf(TestData.song("p1", 0, spotifyId = "same-track"))
        )

        var threw = false
        try {
            songRepo.insertAll(listOf(TestData.song("p1", 1, spotifyId = "same-track")))
        } catch (e: SQLiteConstraintException) {
            threw = true
        }

        assertTrue("UNIQUE(playlist_id, spotify_id) surfaced", threw)
        assertTrue("original row untouched", songRepo.countForPlaylist("p1") == 1)
    }

    @Test
    fun duplicateSpotifyIdAcrossPlaylistsAllowed() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())

        playlistRepo.savePlaylistWithSongs(
            TestData.playlist("p1"),
            listOf(TestData.song("p1", 0, spotifyId = "same-track"))
        )
        playlistRepo.savePlaylistWithSongs(
            TestData.playlist("p2"),
            listOf(TestData.song("p2", 0, spotifyId = "same-track"))
        )

        assertTrue("same track allowed in two playlists", playlistRepo.getPlaylist("p1") != null)
        assertTrue("same track allowed in two playlists", playlistRepo.getPlaylist("p2") != null)
    }

    @Test
    fun orphanSongInsertFailsForeignKey() = runTest {
        val songRepo = SongRepository(db.songDao(), db.transactionRunner())
        var threw = false
        try {
            songRepo.insertAll(listOf(TestData.song("missing-playlist", 0)))
        } catch (e: SQLiteConstraintException) {
            threw = true
        }
        assertTrue("FK violation surfaced", threw)
    }
}
