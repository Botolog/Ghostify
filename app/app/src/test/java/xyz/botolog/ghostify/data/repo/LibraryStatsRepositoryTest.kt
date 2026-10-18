package xyz.botolog.ghostify.data.repo

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.model.LibraryStats

/**
 * JVM tests for the "Library" widget's aggregate read path.
 *
 * The repository exists solely to combine the four scalar `COUNT(*)` queries into one
 * [LibraryStats]; these tests pin that the combine wires each count to the right field
 * (a swapped pair would silently show "songs with lyrics" as "downloaded") and that the
 * one-shot cold read does the same.
 */
class LibraryStatsRepositoryTest {

    private val songs: SongDao = mockk()
    private val playlists: PlaylistDao = mockk()
    private val repository = LibraryStatsRepository(songs, playlists)

    @Test
    fun observeCombinesTheFourCountsInOrder() = runTest {
        every { songs.observeSongCount() } returns flowOf(10)
        every { songs.observeDownloadedCount() } returns flowOf(7)
        every { songs.observeLyricsCount() } returns flowOf(4)
        every { playlists.observeCount() } returns flowOf(3)

        assertEquals(
            LibraryStats(totalSongs = 10, downloadedSongs = 7, songsWithLyrics = 4, playlists = 3),
            repository.observe().first(),
        )
    }

    @Test
    fun getReadsTheSameFourCounts() = runTest {
        coEvery { songs.countSongs() } returns 10
        coEvery { songs.countDownloadedSongs() } returns 7
        coEvery { songs.countSongsWithLyrics() } returns 4
        coEvery { playlists.count() } returns 3

        assertEquals(
            LibraryStats(totalSongs = 10, downloadedSongs = 7, songsWithLyrics = 4, playlists = 3),
            repository.get(),
        )
    }

    @Test
    fun observeReportsAnEmptyLibrary() = runTest {
        every { songs.observeSongCount() } returns flowOf(0)
        every { songs.observeDownloadedCount() } returns flowOf(0)
        every { songs.observeLyricsCount() } returns flowOf(0)
        every { playlists.observeCount() } returns flowOf(0)

        assertEquals(LibraryStats.EMPTY, repository.observe().first())
    }
}
