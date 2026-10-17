package xyz.botolog.ghostify.download

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class DownloadQueueRunnerTest {

    // ── Fakes ─────────────────────────────────────────────────────

    private class FakeDownloadRepository(
        initialSongs: List<SongRecord>,
        initialPlaylists: Map<String, PlaylistRecord> = emptyMap(),
    ) : DownloadRepository {

        data class SetStatusCall(
            val songId: String,
            val status: DownloadStatus,
            val filePath: String?,
            val error: String?,
            val lyrics: String?,
        )

        private val songs = initialSongs.associateBy { it.id }.toMutableMap()
        private val playlists = initialPlaylists.toMutableMap()
        val setStatusCalls = mutableListOf<SetStatusCall>()
        val playlistCoverUpdates = mutableListOf<Pair<String, String?>>()

        /**
         * Number of full-playlist reads. Progress must be O(1) in reads — the old
         * implementation called this twice per song.
         */
        var songsForCalls = 0
            private set

        /** Number of live playlist subscriptions (the runner must not subscribe). */
        var observeSongsCalls = 0
            private set

        override suspend fun songsFor(playlistId: String): List<SongRecord> {
            songsForCalls++
            return songs.values.toList()
        }

        override fun observeSongs(playlistId: String): Flow<List<SongRecord>> {
            observeSongsCalls++
            return flowOf(songs.values.toList())
        }

        override suspend fun getSong(songId: String): SongRecord? =
            songs[songId]

        override suspend fun setStatus(
            songId: String,
            status: DownloadStatus,
            filePath: String?,
            error: String?,
            lyrics: String?,
        ) {
            setStatusCalls.add(SetStatusCall(songId, status, filePath, error, lyrics))
            songs[songId]?.let { songs[songId] = it.copy(status = status) }
        }

        override suspend fun setStatuses(songIds: Collection<String>, status: DownloadStatus) {
            songIds.forEach { id ->
                songs[id]?.let { songs[id] = it.copy(status = status) }
            }
        }

        override suspend fun getPlaylistStatus(playlistId: String): PlaylistStatus? =
            playlists[playlistId]?.status ?: PlaylistStatus.READY

        override suspend fun getPlaylist(playlistId: String): PlaylistRecord? =
            playlists[playlistId]

        override suspend fun setPlaylistStatus(playlistId: String, status: PlaylistStatus) {
            // no-op
        }

        override suspend fun allPlaylistIds(): List<String> =
            songs.values.map { it.playlistId }.distinct()

        override suspend fun updateLyrics(songId: String, lyrics: String?) {
            // no-op – not exercised by these tests
        }

        override suspend fun updateYoutubeMeta(songId: String, ytUrl: String?, ytName: String?, ytChannel: String?) {
            // no-op – not exercised by these tests
        }

        override suspend fun updateDownloadMeta(songId: String, bitrate: Int?, fileSize: Long?, downloadedAt: Long?) {
            // no-op – not exercised by these tests
        }

        override suspend fun updateLyricsSource(songId: String, source: String?) {
            // no-op – not exercised by these tests
        }

        override suspend fun updateLyricsEdited(songId: String, edited: Boolean) {
            // no-op – not exercised by these tests
        }

        override suspend fun updateCoverArtLocalPath(songId: String, path: String?) {
            // no-op – not exercised by these tests
        }

        override suspend fun updatePlaylistCoverArtLocalPath(playlistId: String, path: String?) {
            playlistCoverUpdates.add(playlistId to path)
            playlists[playlistId]?.let { playlists[playlistId] = it.copy(coverArtLocalPath = path) }
        }
    }

    private class FakeTrackDownloader(
        private val resultMap: Map<String, TrackDownloadResult>,
    ) : TrackDownloader {

        override suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult =
            resultMap[song.id] ?: error("No result configured for song ${song.id}")

        override suspend fun cancel(songId: String) {
            // no-op
        }
    }

    /** Reports a configured fraction for the song, then returns its result. */
    private class FractionReportingDownloader(
        private val results: Map<String, TrackDownloadResult>,
        private val fractions: Map<String, Float> = emptyMap(),
    ) : TrackDownloader {

        override suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult {
            fractions[song.id]?.let(onProgress)
            return results[song.id] ?: error("No result configured for song ${song.id}")
        }

        override suspend fun cancel(songId: String) {
            // no-op
        }
    }

    /** Tracks in-flight downloads and the distinct worker coroutines observed. */
    private class ConcurrencyTrackingDownloader(
        private val delayMillis: Long = 1L,
    ) : TrackDownloader {

        private val inFlight = AtomicInteger(0)
        val started = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        private val workerJobs = java.util.Collections.synchronizedSet(mutableSetOf<Job>())

        /** Number of distinct coroutines that ever performed a download. */
        val distinctWorkerCoroutines: Int get() = workerJobs.size

        override suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult {
            coroutineContext[Job]?.let { workerJobs.add(it) }
            started.incrementAndGet()
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(delayMillis)
                return TrackDownloadResult(songId = song.id, filePath = "/dl/${song.id}.mp3")
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun cancel(songId: String) {
            // no-op
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun song(
        id: String = "s1",
        position: Int = 0,
        status: DownloadStatus = DownloadStatus.PENDING,
    ) = SongRecord(
        id = id,
        playlistId = "pl1",
        spotifyId = id,
        title = "Song $id",
        artists = "Artist",
        position = position,
        status = status,
    )

    private fun successResult(songId: String, filePath: String = "/dl/$songId.mp3", lyrics: String? = null) =
        TrackDownloadResult(songId = songId, filePath = filePath, lyrics = lyrics)

    private fun failedResult(songId: String, message: String = "download failed") =
        TrackDownloadResult(songId = songId, error = DownloadError.Generic(message))

    // ── Tests: applyTrackResult via run() ─────────────────────────

    @Test
    fun `successful download passes lyrics to repo setStatus`() = runBlocking {
        val s1 = song("s1")
        val repo = FakeDownloadRepository(listOf(s1))
        val downloader = FakeTrackDownloader(
            mapOf("s1" to successResult("s1", lyrics = "La la la")),
        )
        val runner = DownloadQueueRunner(repo, downloader, concurrency = 1)

        val outcome = runner.run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)

        val downloaded = repo.setStatusCalls.filter { it.status == DownloadStatus.DOWNLOADED }
        assertEquals(1, downloaded.size)
        assertEquals("La la la", downloaded[0].lyrics)
        assertEquals("/dl/s1.mp3", downloaded[0].filePath)
    }

    @Test
    fun `successful download without lyrics passes null lyrics to repo`() = runBlocking {
        val s1 = song("s1")
        val repo = FakeDownloadRepository(listOf(s1))
        val downloader = FakeTrackDownloader(
            mapOf("s1" to successResult("s1", lyrics = null)),
        )
        val runner = DownloadQueueRunner(repo, downloader, concurrency = 1)

        val outcome = runner.run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)

        val downloaded = repo.setStatusCalls.filter { it.status == DownloadStatus.DOWNLOADED }
        assertEquals(1, downloaded.size)
        assertNull(downloaded[0].lyrics)
    }

    @Test
    fun `failed download has null lyrics in setStatus call`() = runBlocking {
        val s1 = song("s1")
        val repo = FakeDownloadRepository(listOf(s1))
        val downloader = FakeTrackDownloader(
            mapOf("s1" to failedResult("s1")),
        )
        val runner = DownloadQueueRunner(repo, downloader, concurrency = 1)

        val outcome = runner.run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)

        val failed = repo.setStatusCalls.filter { it.status == DownloadStatus.FAILED }
        assertEquals(1, failed.size)
        assertNull(failed[0].lyrics)
    }

    @Test
    fun `successful downloads pass lyrics for each track independently`() = runBlocking {
        val s1 = song("s1", position = 0)
        val s2 = song("s2", position = 1)
        val repo = FakeDownloadRepository(listOf(s1, s2))
        val downloader = FakeTrackDownloader(
            mapOf(
                "s1" to successResult("s1", lyrics = "Lyrics for s1"),
                "s2" to successResult("s2", lyrics = null),
            ),
        )
        val runner = DownloadQueueRunner(repo, downloader, concurrency = 1)

        val outcome = runner.run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)

        val downloaded = repo.setStatusCalls.filter { it.status == DownloadStatus.DOWNLOADED }
        assertEquals(2, downloaded.size)

        val s1Call = downloaded.first { it.songId == "s1" }
        assertEquals("Lyrics for s1", s1Call.lyrics)

        val s2Call = downloaded.first { it.songId == "s2" }
        assertNull(s2Call.lyrics)
    }

    // ── Tests: progress is incremental (no O(N²) DB reloads) ──────

    @Test
    fun `runner reads the playlist once for a large selection`() = runTest {
        val songs = (0 until 50).map { song("s$it", position = it) }
        val repo = FakeDownloadRepository(songs)
        val results = songs.associate { it.id to successResult(it.id) }
        val runner = DownloadQueueRunner(repo, FakeTrackDownloader(results), concurrency = 4)

        val outcome = runner.run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)
        assertTrue(
            "songsFor must not scale with playlist size, saw ${repo.songsForCalls}",
            repo.songsForCalls <= 2,
        )
        assertTrue(repo.songsForCalls < songs.size)
        assertEquals(0, repo.observeSongsCalls)
    }

    @Test
    fun `progress snapshots track mixed success and failure`() = runBlocking {
        val s1 = song("s1", position = 0)
        val s2 = song("s2", position = 1)
        val s3 = song("s3", position = 2)
        val repo = FakeDownloadRepository(listOf(s1, s2, s3))
        val downloader = FractionReportingDownloader(
            results = mapOf(
                "s1" to successResult("s1"),
                "s2" to failedResult("s2"),
                "s3" to successResult("s3"),
            ),
            fractions = mapOf("s1" to 0.4f),
        )
        val runner = DownloadQueueRunner(repo, downloader, concurrency = 1)
        val snapshots = mutableListOf<DownloadProgress>()
        val reportedFractions = mutableListOf<Pair<String, Float>>()

        val outcome = runner.run(
            playlistId = "pl1",
            onProgress = { snapshots += it },
            onSongProgress = { id, fraction -> reportedFractions += id to fraction },
        )

        assertEquals(RunOutcome.COMPLETED, outcome)

        // Every snapshot still covers the whole playlist, not a delta.
        assertTrue(snapshots.isNotEmpty())
        assertTrue(snapshots.all { it.total == 3 && it.perSong.size == 3 })
        // A mid-run snapshot saw s1 in flight rather than still QUEUED.
        assertTrue(
            snapshots.any {
                it.state == DownloadRunState.RUNNING &&
                    it.perSong["s1"]?.status == DownloadStatus.DOWNLOADING
            },
        )
        // The mid-run fraction reached the per-song callback.
        assertTrue(reportedFractions.contains("s1" to 0.4f))

        val last = snapshots.last()
        assertEquals(DownloadRunState.COMPLETED, last.state)
        assertEquals(3, last.total)
        assertEquals(3, last.done)
        assertEquals(100f, last.overallPercent, 0.001f)
        assertEquals(2, last.downloaded)
        assertEquals(DownloadStatus.DOWNLOADED, last.perSong["s1"]?.status)
        assertEquals(DownloadStatus.FAILED, last.perSong["s2"]?.status)
        assertEquals("download failed", last.perSong["s2"]?.error)
        assertEquals(DownloadStatus.DOWNLOADED, last.perSong["s3"]?.status)
    }

    @Test
    fun `concurrency is bounded for a 500-song selection`() = runTest {
        val songs = (0 until 500).map { song("s$it", position = it) }
        val repo = FakeDownloadRepository(songs)
        val downloader = ConcurrencyTrackingDownloader()
        val runner = DownloadQueueRunner(repo, downloader, concurrency = 4)

        val outcome = runner.run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)
        assertEquals(500, downloader.started.get())
        assertTrue(
            "expected at most 4 concurrent downloads but saw ${downloader.maxInFlight.get()}",
            downloader.maxInFlight.get() <= 4,
        )
        // The old engine launched one coroutine per song (500 here); the pool must
        // stay bounded by the configured concurrency, not the playlist size.
        assertTrue(
            "expected at most 4 worker coroutines but saw ${downloader.distinctWorkerCoroutines}",
            downloader.distinctWorkerCoroutines <= 4,
        )
    }
}
