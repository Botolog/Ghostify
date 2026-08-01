package com.ghostify.sync

import com.ghostify.data.model.SongStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-068 — concurrent sync + manual download: no deadlock, no duplicate downloads.
 *
 * [ConcurrentDownloadManager] models the real DownloadManager's two guarantees:
 *  - claims are serialized by a queue lock and only ever transition
 *    PENDING -> QUEUED (the Room equivalent is the single-writer SQLite
 *    transaction behind `updateStatus`),
 *  - completed downloads are counted per track, so a double-claim is a
 *    detectable double download.
 *
 * The sync path runs the diff under the same "transaction" and never touches
 * QUEUED/DOWNLOADING rows, so the two never race to enqueue the same track.
 */
class SyncUseCaseConcurrencyTest {

    @TempDir
    lateinit var temp: File

    private fun harness() = TestHarness(temp)

    private fun song(id: String, spotifyId: String, title: String, position: Int, status: SongStatus, filePath: String?) =
        com.ghostify.data.db.entity.SongEntity(
            id = id,
            playlistId = "pl1",
            spotifyId = spotifyId,
            title = title,
            artists = "Artist",
            album = "Album",
            durationMs = 180_000,
            coverUrl = null,
            ytId = null,
            filePath = filePath,
            status = status,
            position = position,
            addedAt = 0L,
        )

    @Test
    fun `T-068 in-flight download is never clobbered or double-enqueued`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        val file3 = h.files.writeFile("Artist - Three.mp3")
        h.songDao.insertAll(
            listOf(
                song("s1", "sp-1", "One", 0, SongStatus.PENDING, null),
                song("s2", "sp-2", "Two", 1, SongStatus.DOWNLOADING, null), // manual download in flight
                song("s3", "sp-3", "Three", 2, SongStatus.DOWNLOADED, file3),
            )
        )
        h.fetcher.setTracks(
            FakeFetcher.track(0, "sp-1", "One"),
            FakeFetcher.track(1, "sp-2", "Two"),
            FakeFetcher.track(2, "sp-3", "Three"),
        )
        val dm = ConcurrentDownloadManager(h.songDao)
        val uc = SyncUseCase(
            playlists = h.playlistDao,
            songs = h.songDao,
            transactions = h.transactions,
            fetcher = h.fetcher,
            files = h.files,
            enqueuer = dm,
            locks = h.locks,
            now = { 1_000L },
            newId = { "song-x" },
        )

        withTimeout(5_000) { uc.syncPlaylist("pl1") }

        // The in-flight track must NOT be reset to PENDING (that would let a
        // second enqueue claim it) and must not be re-enqueued.
        val s2 = h.songDao.getById("s2")!!
        assertEquals(SongStatus.DOWNLOADING, s2.status, "in-flight status must survive sync")
        assertEquals(0, dm.claimCounts["s2"] ?: 0, "in-flight track never re-claimed")

        // The missing-file track is re-queued and claimed exactly once.
        assertEquals(1, dm.claimCounts["s1"] ?: 0)

        // The already-downloaded track is untouched.
        assertEquals(SongStatus.DOWNLOADED, h.songDao.getById("s3")!!.status)
        assertTrue(h.files.exists(file3))
    }

    @Test
    fun `T-068 concurrent sync and manual download never deadlock or double-download`() = runBlocking {
        repeat(30) { round ->
            val h = harness()
            h.seedPlaylist()
            val file1 = h.files.writeFile("Artist - One.mp3")
            h.songDao.insertAll(
                listOf(
                    song("s1", "sp-1", "One", 0, SongStatus.DOWNLOADED, file1),
                    song("s2", "sp-2", "Two", 1, SongStatus.PENDING, null),
                    song("s3", "sp-3", "Three", 2, SongStatus.PENDING, null),
                )
            )
            h.fetcher.setTracks(
                FakeFetcher.track(0, "sp-1", "One"),
                FakeFetcher.track(1, "sp-2", "Two"),
                FakeFetcher.track(2, "sp-3", "Three"),
            )
            val dm = ConcurrentDownloadManager(h.songDao)
            val uc = SyncUseCase(
                playlists = h.playlistDao,
                songs = h.songDao,
                transactions = h.transactions,
                fetcher = h.fetcher,
                files = h.files,
                enqueuer = dm, // sync's enqueue and the manual download share the claim
                locks = h.locks,
                now = { 1_000L },
                newId = { "song-x-$round" },
            )

            val syncJob = async { uc.syncPlaylist("pl1") }
            val manualJob = async { dm.userDownloadAll("pl1") }

            withTimeout(5_000) { // deadlock => TimeoutCancellationException => test fails
                syncJob.await()
                manualJob.await()
            }

            dm.drainQueue("pl1")
            // Every claimed track was claimed exactly once -> no duplicate downloads.
            assertEquals(
                emptyList(),
                dm.claimCounts.entries.filter { it.value > 1 },
                "round $round: a track was claimed more than once",
            )
            // The two PENDING tracks are each downloaded exactly once.
            assertEquals(setOf("s2", "s3"), dm.downloads, "round $round: each pending track downloaded once")
        }
    }
}

/**
 * Stand-in for the real DownloadManager: an atomic claim queue plus a simulated
 * worker that completes each queued track. Shared with the sync's enqueuer, so
 * both paths contend for the same claims exactly as they would in the app.
 */
class ConcurrentDownloadManager(private val dao: FakeSongDao) : DownloadEnqueuer {

    private val queueLock = Mutex()

    /** PENDING -> QUEUED transitions, counted per song id. */
    val claimCounts = LinkedHashMap<String, Int>()

    /** Song ids whose download completed. */
    val downloads = LinkedHashSet<String>()

    /** Sync path: enqueue every PENDING track. */
    override suspend fun enqueuePendingDownloads(playlistId: String) = claimAll(playlistId)

    /** Manual "Download all" path — same atomic claim. */
    suspend fun userDownloadAll(playlistId: String) = claimAll(playlistId)

    private suspend fun claimAll(playlistId: String) {
        queueLock.withLock {
            for (song in dao.getSongsForPlaylist(playlistId).toList()) {
                if (song.status == SongStatus.PENDING) {
                    dao.update(song.copy(status = SongStatus.QUEUED))
                    claimCounts.merge(song.id, 1, Int::plus)
                }
            }
        }
    }

    /** Simulated worker: finish every QUEUED track (no real file needed here). */
    suspend fun drainQueue(playlistId: String) {
        for (song in dao.getSongsForPlaylist(playlistId).toList()) {
            if (song.status == SongStatus.QUEUED) {
                dao.update(song.copy(status = SongStatus.DOWNLOADING))
                dao.update(song.copy(status = SongStatus.DOWNLOADED, filePath = "/store/${song.id}.mp3"))
                downloads.add(song.id)
            }
        }
    }
}
