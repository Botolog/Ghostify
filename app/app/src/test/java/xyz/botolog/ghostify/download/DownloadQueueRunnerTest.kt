package xyz.botolog.ghostify.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueRunnerTest {

    // ── Fakes ─────────────────────────────────────────────────────

    private class FakeDownloadRepository(
        initialSongs: List<SongRecord>,
    ) : DownloadRepository {

        data class SetStatusCall(
            val songId: String,
            val status: DownloadStatus,
            val filePath: String?,
            val error: String?,
            val lyrics: String?,
        )

        private val songs = initialSongs.associateBy { it.id }.toMutableMap()
        val setStatusCalls = mutableListOf<SetStatusCall>()

        override suspend fun songsFor(playlistId: String): List<SongRecord> =
            songs.values.toList()

        override fun observeSongs(playlistId: String): Flow<List<SongRecord>> =
            flowOf(songs.values.toList())

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
            PlaylistStatus.READY

        override suspend fun setPlaylistStatus(playlistId: String, status: PlaylistStatus) {
            // no-op
        }

        override suspend fun allPlaylistIds(): List<String> =
            songs.values.map { it.playlistId }.distinct()

        override suspend fun updateLyrics(songId: String, lyrics: String?) {
            // no-op – not exercised by these tests
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
}
