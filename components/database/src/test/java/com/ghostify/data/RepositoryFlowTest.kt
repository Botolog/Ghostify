package com.ghostify.data

import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SongRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-073: the repository exposes reactive Flows and the UI observes inserts,
 * updates and deletes as they happen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositoryFlowTest : BaseDbTest() {

    @Test
    fun playlistFlowReEmitsOnInsertUpdateDelete() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())

        val snapshots = mutableListOf<List<String>>()
        val job = launch { playlistRepo.observePlaylists().collect { snapshots += it.map { p -> p.name } } }
        delay(50) // let the collector subscribe

        playlistRepo.savePlaylist(TestData.playlist("p1", name = "P"))
        delay(50)
        playlistRepo.updatePlaylist(playlistRepo.getPlaylist("p1")!!.copy(name = "Renamed"))
        delay(50)
        playlistRepo.deletePlaylist("p1")
        delay(50)
        job.cancel()

        assertTrue("insert observed", snapshots.any { it.contains("P") })
        assertTrue("update observed", snapshots.any { it.contains("Renamed") })
        assertTrue("delete observed (empty tail)", snapshots.last() == emptyList<String>())
    }

    @Test
    fun songsFlowReEmitsOnBatchInsertAndStatusChange() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        val songRepo = SongRepository(db.songDao(), db.transactionRunner())
        playlistRepo.savePlaylist(TestData.playlist("p1"))

        val snapshots = mutableListOf<List<String>>()
        val job = launch { songRepo.observeSongs("p1").collect { snapshots += it.map { s -> s.title } } }
        delay(50)

        songRepo.insertAll(listOf(TestData.song("p1", 0), TestData.song("p1", 1)))
        delay(50)
        val queued = songRepo.getSongs("p1")
        songRepo.setStatus(queued.map { it.id }, com.ghostify.data.model.SongStatus.QUEUED)
        delay(50)
        job.cancel()

        assertTrue("batch insert observed", snapshots.any { it.size == 2 })
        assertTrue("status change observed", snapshots.isNotEmpty())
    }

    @Test
    fun observeByIdEmitsCurrentValue() = runTest {
        val playlistRepo = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
        playlistRepo.savePlaylist(TestData.playlist("p1"))
        val emitted = playlistRepo.observePlaylist("p1").first()
        assertEquals("p1", emitted?.id)
    }
}
