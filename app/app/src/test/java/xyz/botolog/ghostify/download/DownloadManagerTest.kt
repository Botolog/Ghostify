package xyz.botolog.ghostify.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Engine/progress tests for [DownloadManager]: fraction coalescing and the
 * guarantee that a finished run does not leave the 1300+-entry fraction map
 * resident.
 */
class DownloadManagerTest {

    // ── Fakes ─────────────────────────────────────────────────────

    private class FakeRepo(initialSongs: List<SongRecord>) : DownloadRepository {

        private val songs = initialSongs.associateBy { it.id }.toMutableMap()

        override suspend fun songsFor(playlistId: String): List<SongRecord> = songs.values.toList()

        override fun observeSongs(playlistId: String): Flow<List<SongRecord>> =
            flowOf(songs.values.toList())

        override suspend fun getSong(songId: String): SongRecord? = songs[songId]

        override suspend fun setStatus(
            songId: String,
            status: DownloadStatus,
            filePath: String?,
            error: String?,
            lyrics: String?,
        ) {
            // Mirrors SongDao.setStatus: filePath is only overwritten when
            // non-null, error is always written (null clears it).
            songs[songId]?.let { songs[songId] = it.copy(status = status, filePath = filePath ?: it.filePath, error = error) }
        }

        override suspend fun setStatuses(songIds: Collection<String>, status: DownloadStatus) {
            songIds.forEach { id -> songs[id]?.let { songs[id] = it.copy(status = status) } }
        }

        override suspend fun getPlaylistStatus(playlistId: String): PlaylistStatus = PlaylistStatus.READY

        override suspend fun getPlaylist(playlistId: String): PlaylistRecord =
            PlaylistRecord(id = playlistId, status = PlaylistStatus.READY)

        override suspend fun setPlaylistStatus(playlistId: String, status: PlaylistStatus) = Unit

        override suspend fun allPlaylistIds(): List<String> = listOf("pl1")

        override suspend fun updateLyrics(songId: String, lyrics: String?) = Unit

        override suspend fun updateYoutubeMeta(songId: String, ytUrl: String?, ytName: String?, ytChannel: String?) = Unit

        override suspend fun updateDownloadMeta(songId: String, bitrate: Int?, fileSize: Long?, downloadedAt: Long?) = Unit

        override suspend fun updateLyricsSource(songId: String, source: String?) = Unit

        override suspend fun updateLyricsEdited(songId: String, edited: Boolean) = Unit

        override suspend fun updateCoverArtLocalPath(songId: String, path: String?) = Unit

        override suspend fun updatePlaylistCoverArtLocalPath(playlistId: String, path: String?) = Unit
    }

    private class FractionThenSuccessDownloader(private val fraction: Float) : TrackDownloader {
        override suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult {
            onProgress(fraction)
            return TrackDownloadResult(songId = song.id, filePath = "/dl/${song.id}.mp3")
        }

        override suspend fun cancel(songId: String) = Unit
    }

    private fun song(id: String, status: DownloadStatus = DownloadStatus.PENDING) = SongRecord(
        id = id,
        playlistId = "pl1",
        spotifyId = id,
        title = "Song $id",
        artists = "Artist",
        position = 0,
        status = status,
    )

    // ── Fractions cleared after the run ───────────────────────────

    @Test
    fun `fractions are cleared after the run completes`() = runBlocking {
        val repo = FakeRepo(listOf(song("s1")))
        val runner = DownloadQueueRunner(repo, FractionThenSuccessDownloader(0.42f), concurrency = 1)
        val manager = DownloadManager(repo, runner, CoroutineScope(Dispatchers.Default))

        assertEquals(RunOutcome.COMPLETED, manager.runSynchronously("pl1"))

        val progress = manager.observeProgress("pl1").first()
        // Had the coalesced 0.42 survived cleanup the fraction would still be
        // 0.42; the terminal DOWNLOADED default of 1f proves the map was dropped.
        assertEquals(1f, progress.perSong.getValue("s1").fraction, 0.0001f)
        assertEquals(1, progress.downloaded)
    }

    // ── FractionCoalescer ─────────────────────────────────────────

    @Test
    fun `first fraction offer is published immediately`() {
        val coalescer = FractionCoalescer(intervalMillis = 200L, clock = { 1_000L })
        assertEquals(mapOf("s1" to 0.1f), coalescer.offer("s1", 0.1f))
    }

    @Test
    fun `offers inside the interval coalesce and keep the latest value per song`() {
        var now = 1_000L
        val coalescer = FractionCoalescer(intervalMillis = 200L, clock = { now })

        coalescer.offer("s1", 0.1f) // published
        assertNull(coalescer.offer("s1", 0.2f))
        assertNull(coalescer.offer("s1", 0.3f))
        assertNull(coalescer.offer("s2", 0.5f))

        now += 200L
        assertEquals(mapOf("s1" to 0.31f, "s2" to 0.5f), coalescer.offer("s1", 0.31f))
    }

    @Test
    fun `flush releases the pending value and is idempotent`() {
        var now = 1_000L
        val coalescer = FractionCoalescer(intervalMillis = 200L, clock = { now })

        coalescer.offer("s1", 0.1f) // published
        assertNull(coalescer.offer("s1", 0.9f))

        assertEquals(mapOf("s1" to 0.9f), coalescer.flush())
        assertNull(coalescer.flush())
    }
}
