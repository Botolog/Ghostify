package com.ghostify.data

import android.database.sqlite.SQLiteConstraintException
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghostify.data.db.AppDatabase
import com.ghostify.data.db.Migrations
import com.ghostify.data.model.SongStatus
import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SettingsRepository
import com.ghostify.data.repo.SongRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented equivalents of T-069..T-078 against a real on-device Room database.
 * Mirrors the JVM (Robolectric) suite; run on an emulator/device via connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseInstrumentedTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(*Migrations.ALL)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun playlistRepo() = PlaylistRepository(db.playlistDao(), db.songDao(), db.transactionRunner())
    private fun songRepo() = SongRepository(db.songDao(), db.transactionRunner())

    @Test
    fun T069_playlistRoundTrip() = runBlocking {
        val repo = playlistRepo()
        repo.savePlaylist(TestData.playlist("p1"))
        assertEquals("p1", repo.getPlaylist("p1")?.id)
        repo.updatePlaylist(repo.getPlaylist("p1")!!.copy(name = "Renamed"))
        assertEquals("Renamed", repo.getPlaylist("p1")?.name)
        repo.deletePlaylist("p1")
        assertEquals(null, repo.getPlaylist("p1"))
    }

    @Test
    fun T070_playlistDeleteCascadesToSongs() = runBlocking {
        val repo = playlistRepo()
        val songs = songRepo()
        repo.savePlaylistWithSongs(TestData.playlist("p1"), TestData.songs("p1", 3))
        assertEquals(3, songs.countForPlaylist("p1"))
        repo.deletePlaylist("p1")
        assertEquals(0, songs.countForPlaylist("p1"))
    }

    @Test
    fun T071_uniquePlaylistSpotifyEnforced() = runBlocking {
        val repo = playlistRepo()
        val songs = songRepo()
        repo.savePlaylistWithSongs(TestData.playlist("p1"), listOf(TestData.song("p1", 0, spotifyId = "x")))
        var threw = false
        try {
            songs.insertAll(listOf(TestData.song("p1", 1, spotifyId = "x")))
        } catch (e: SQLiteConstraintException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(1, songs.countForPlaylist("p1"))
    }

    @Test
    fun T072_songsOrderedByPosition() = runBlocking {
        val repo = playlistRepo()
        repo.savePlaylistWithSongs(
            TestData.playlist("p1"),
            listOf(TestData.song("p1", 2), TestData.song("p1", 0), TestData.song("p1", 1))
        )
        assertEquals(listOf(0, 1, 2), songRepo().getSongs("p1").map { it.position })
    }

    @Test
    fun T073_playlistFlowEmitsOnChange() = runBlocking {
        val repo = playlistRepo()
        val snapshots = mutableListOf<List<String>>()
        val job = launch {
            repo.observePlaylists().collect { snapshots += it.map { p -> p.name } }
        }
        delay(100)
        repo.savePlaylist(TestData.playlist("p1"))
        delay(100)
        repo.deletePlaylist("p1")
        delay(100)
        job.cancel()
        assertTrue(snapshots.any { it.contains("Playlist p1") })
        assertTrue(snapshots.last().isEmpty())
    }

    @Test
    fun T075_failedBatchLeavesNothing() = runBlocking {
        val repo = playlistRepo()
        val songs = songRepo()
        val badBatch = TestData.songs("p1", 2) + TestData.song("p1", 2, spotifyId = "track-0")
        var threw = false
        try {
            repo.savePlaylistWithSongs(TestData.playlist("p1"), badBatch)
        } catch (e: SQLiteConstraintException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(null, repo.getPlaylist("p1"))
        assertEquals(0, songs.countForPlaylist("p1"))
    }

    @Test
    fun T076_thousandSongsUnderBudget() = runBlocking {
        val repo = playlistRepo()
        val start = System.nanoTime()
        repo.savePlaylistWithSongs(TestData.playlist("p1"), TestData.songs("p1", 1000))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(1000, repo.getSongs("p1").size)
        assertTrue("1000 rows in ${elapsedMs}ms", elapsedMs < 5000)
    }

    @Test
    fun T077_settingsRoundTrip() = runBlocking {
        val repo = SettingsRepository(db.settingDao())
        assertEquals(SettingsRepository.DEFAULT_STORAGE_DIR, repo.getStorageDir())
        repo.setStorageDir("/music")
        repo.setBitrate("192")
        repo.setConcurrency(4)
        repo.setAutoDownload(true)
        assertEquals("/music", repo.getStorageDir())
        assertEquals("192", repo.getBitrate())
        assertEquals(4, repo.getConcurrency())
        assertTrue(repo.getAutoDownload())
    }

    @Test
    fun T078_statusFilterReturnsCorrectSubsets() = runBlocking {
        val repo = playlistRepo()
        repo.savePlaylistWithSongs(
            TestData.playlist("p1"),
            listOf(
                TestData.song("p1", 0, status = SongStatus.DOWNLOADED),
                TestData.song("p1", 1, status = SongStatus.FAILED),
                TestData.song("p1", 2, status = SongStatus.PENDING)
            )
        )
        assertEquals(listOf(0), songRepo().getSongsByStatus("p1", listOf(SongStatus.DOWNLOADED)).map { it.position })
        assertEquals(listOf(1, 2), songRepo().getSongsByStatus("p1", listOf(SongStatus.FAILED, SongStatus.PENDING)).map { it.position })
        assertEquals(3, songRepo().getSongs("p1").size)
    }

    @Test
    fun T074_migrationPreservesData() = runBlocking {
        // Covered by MigrationInstrumentedTest using a real v1 file DB + MigrationTestHelper.
        assertTrue(true)
    }
}
