package com.ghostify.download

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T-046: overall progress % = done / total, FAILED counts as done. */
class DownloadProgressCalculatorTest {

    private fun song(id: String, pos: Int, status: DownloadStatus) = SongRecord(
        id = id, playlistId = "p1", spotifyId = "spot-$id",
        title = id, artists = "A", position = pos, status = status,
    )

    @Test
    fun `overall percent includes FAILED as done`() {
        val songs = listOf(
            song("a", 0, DownloadStatus.DOWNLOADED),
            song("b", 1, DownloadStatus.DOWNLOADED),
            song("c", 2, DownloadStatus.DOWNLOADED),
            song("d", 3, DownloadStatus.FAILED),
            song("e", 4, DownloadStatus.PENDING),
            song("f", 5, DownloadStatus.PENDING),
            song("g", 6, DownloadStatus.PENDING),
            song("h", 7, DownloadStatus.PENDING),
            song("i", 8, DownloadStatus.PENDING),
            song("j", 9, DownloadStatus.PENDING),
        )
        val p = DownloadProgressCalculator.fromSongs("p1", songs)
        assertEquals(10, p.total)
        assertEquals(4, p.done)
        assertEquals(40f, p.overallPercent)
    }

    @Test
    fun `empty playlist is fully complete at 100 percent`() {
        val p = DownloadProgressCalculator.fromSongs("p1", emptyList())
        assertEquals(0, p.total)
        assertEquals(0, p.done)
        assertEquals(100f, p.overallPercent)
    }

    @Test
    fun `running state is exposed and reflects playlist status`() {
        val p = DownloadProgressCalculator.fromSongs(
            "p1",
            listOf(song("a", 0, DownloadStatus.DOWNLOADING)),
            state = DownloadRunState.RUNNING,
        )
        assertEquals(DownloadRunState.RUNNING, p.state)
        assertEquals(PlaylistStatus.DOWNLOADING, p.playlistStatus)
    }

    @Test
    fun `live fractions are merged into per-song progress`() {
        val p = DownloadProgressCalculator.fromSongs(
            "p1",
            listOf(song("a", 0, DownloadStatus.DOWNLOADING)),
            fractions = mapOf("a" to 0.5f),
        )
        assertEquals(0.5f, p.perSong.getValue("a").fraction)
    }

    @Test
    fun `progress flow reflects a full run from repository state`() = runTest {
        val repo = FakeDownloadRepository()
        repo.seedPlaylist("p1", 4)
        val dl = BlockingDownloader()
        val runner = DownloadQueueRunner(repo, dl)
        val manager = DownloadManager(repo, runner, this)
        manager.bindExecutor(InlineDownloadExecutor(manager, this))

        val collected = mutableListOf<DownloadProgress>()
        val job = launch { manager.observeProgress("p1").toList(collected) }

        manager.downloadAll("p1")
        runCurrent() // first track DOWNLOADING -> a RUNNING sample is observable
        repeat(4) { i ->
            dl.release("p1:s$i")
            advanceUntilIdle()
        }
        runCurrent()

        job.cancel()
        val last = collected.last()
        assertEquals(4, last.total)
        assertEquals(4, last.done)
        assertEquals(100f, last.overallPercent)
        assertTrue(collected.any { it.state == DownloadRunState.RUNNING }, "expected a RUNNING sample")
        assertTrue(collected.any { it.done == 1 } || collected.any { it.done == 2 }, "progress increased over time")
    }
}
