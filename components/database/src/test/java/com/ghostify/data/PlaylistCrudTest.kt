package com.ghostify.data

import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SongRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-069: `playlists` insert / read / update / delete round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaylistCrudTest : BaseDbTest() {

    private fun repos() = Triple(
        PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner()),
        SongRepository(db.songDao(), db.transactionRunner()),
        db
    )

    @Test
    fun insertReadUpdateDeleteRoundTrip() = runTest {
        val repo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        val p = TestData.playlist("p1")

        repo.savePlaylist(p)
        assertTrue("inserted row readable", repo.getPlaylist("p1") == p)

        repo.updatePlaylist(p.copy(name = "Renamed", trackCount = 7))
        val updated = repo.getPlaylist("p1")!!
        assertTrue("update persisted", updated.name == "Renamed" && updated.trackCount == 7)

        repo.deletePlaylist("p1")
        assertTrue("delete removed row", repo.getPlaylist("p1") == null)
    }

    @Test
    fun saveSameSpotifyPlaylistTwiceIsIdempotent() = runTest {
        val repo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        val songRepo = SongRepository(db.songDao(), db.transactionRunner())

        repo.savePlaylistWithSongs(
            TestData.playlist("a", spotifyId = "spot-1", name = "First"),
            TestData.songs("a", 2)
        )
        repo.savePlaylistWithSongs(
            TestData.playlist("b", spotifyId = "spot-1", name = "Second"),
            TestData.songs("b", 3)
        )

        assertTrue("old copy removed", repo.getPlaylist("a") == null)
        assertTrue("new copy present", repo.getPlaylist("b")?.name == "Second")
        assertTrue("no orphan songs", songRepo.getSongs("a").isEmpty())
        assertTrue("new batch intact", songRepo.getSongs("b").size == 3)
    }
}
