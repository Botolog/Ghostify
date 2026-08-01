package com.ghostify.download

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.ghostify.download.data.DownloadDatabase
import com.ghostify.download.data.DownloadDao
import com.ghostify.download.data.PlaylistEntity
import com.ghostify.download.data.RoomDownloadRepository
import com.ghostify.download.data.SongEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE / INSTRUMENTATION ONLY (requires an emulator or device).
 *
 * These tests exercise the real WorkManager + [DownloadWorker] + Room stack:
 *   - T-050: a download enqueued through WorkManager keeps running even though
 *     the app is "backgrounded" (WorkManager owns the worker's lifecycle).
 *   - T-051: if a worker is killed mid-download, the next run recovers the
 *     in-flight track to PENDING and completes consistently.
 *
 * They cannot run on the JVM; run `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DownloadWorkerInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var db: DownloadDatabase
    private lateinit var dao: DownloadDao
    private lateinit var repo: RoomDownloadRepository
    private lateinit var downloader: ScriptedDownloader

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadDao()
        repo = RoomDownloadRepository(dao)
        downloader = ScriptedDownloader()
        DownloadProvider.overrideForTesting(context as Application, repo, downloader)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        DownloadProvider.reset()
        db.close()
    }

    @Test
    fun t050_download_continues_when_app_is_backgrounded() = runTest {
        seedPlaylist("p1", 4)

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                DownloadScheduler.uniqueName("p1"),
                ExistingWorkPolicy.KEEP,
                DownloadWorker.buildRequest("p1"),
            )
        WorkManagerTestInitHelper.getTestDriver(context)!!.runAllUniqueWork()

        // WorkManager executed the worker off the UI thread; the downloads ran.
        val songs = repo.songsFor("p1")
        assertTrue("all downloaded: $songs", songs.all { it.status == DownloadStatus.DOWNLOADED })
        assertEquals(listOf("p1:s0", "p1:s1", "p1:s2", "p1:s3"), downloader.downloads)
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("p1"))
    }

    @Test
    fun t051_process_killed_mid_download_recovers_in_flight_track() = runTest {
        // Simulate a process killed mid-download: a song left DOWNLOADING.
        seedPlaylist("p1", 3)
        dao.setStatus("p1:s0", DownloadStatus.DOWNLOADING.name, null, null)

        // "Restart": enqueue and run the worker. The runner resets the in-flight
        // track to PENDING, then downloads everything consistently.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                DownloadScheduler.uniqueName("p1"),
                ExistingWorkPolicy.KEEP,
                DownloadWorker.buildRequest("p1"),
            )
        WorkManagerTestInitHelper.getTestDriver(context)!!.runAllUniqueWork()

        val after = repo.songsFor("p1")
        assertTrue("recovered and completed: $after", after.all { it.status == DownloadStatus.DOWNLOADED })
        assertEquals(3, downloader.downloads.size)
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("p1"))
    }

    // -- Room seeding helpers -------------------------------------------------

    private suspend fun seedPlaylist(playlistId: String, count: Int) {
        dao.insertPlaylist(PlaylistEntity(id = playlistId, status = PlaylistStatus.NEW.name))
        dao.insertSongs(
            (0 until count).map { i ->
                SongEntity(
                    id = "$playlistId:s$i",
                    playlistId = playlistId,
                    spotifyId = "spot$i",
                    title = "Song $i",
                    artists = "Artist",
                    position = i,
                    status = DownloadStatus.PENDING.name,
                )
            },
        )
    }
}
