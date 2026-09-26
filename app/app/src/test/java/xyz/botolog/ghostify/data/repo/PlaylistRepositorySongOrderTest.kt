package xyz.botolog.ghostify.data.repo

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.TransactionRunner
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.db.entity.SongPositionUpdate

class PlaylistRepositorySongOrderTest {

    private lateinit var songDao: SongDao
    private lateinit var repository: PlaylistRepository
    private val written = mutableListOf<List<SongPositionUpdate>>()
    private var transactionCount = 0

    @Before
    fun setup() {
        written.clear()
        transactionCount = 0
        songDao = mockk(relaxed = true)
        coEvery { songDao.updatePositions(any()) } answers {
            written += firstArg<List<SongPositionUpdate>>()
        }
        repository = PlaylistRepository(
            playlistDao = mockk<PlaylistDao>(relaxed = true),
            songDao = songDao,
            transactions = object : TransactionRunner {
                override suspend fun <R> withinTransaction(block: suspend () -> R): R {
                    transactionCount++
                    return block()
                }
            },
        )
    }

    @Test
    fun applySongOrderRewritesPositionsInRequestedOrder() = runTest {
        givenStoredSongs("a" to 0, "b" to 1, "c" to 2)

        val changed = repository.applySongOrder(PLAYLIST_ID, listOf("c", "a", "b"))

        assertTrue(changed)
        assertEquals(listOf("c", "a", "b"), writtenIds())
        assertEquals(listOf(0, 1, 2), writtenPositions())
    }

    @Test
    fun applySongOrderOnlyWritesRowsWhosePositionChanges() = runTest {
        givenStoredSongs("a" to 0, "b" to 1, "c" to 2)

        val changed = repository.applySongOrder(PLAYLIST_ID, listOf("a", "c", "b"))

        assertTrue(changed)
        assertEquals(
            listOf(SongPositionUpdate("c", 1), SongPositionUpdate("b", 2)),
            written.flatten(),
        )
    }

    @Test
    fun applySongOrderWritesNothingWhenStoredOrderAlreadyMatches() = runTest {
        givenStoredSongs("a" to 0, "b" to 1, "c" to 2)

        val changed = repository.applySongOrder(PLAYLIST_ID, listOf("a", "b", "c"))

        assertFalse(changed)
        coVerify(exactly = 0) { songDao.updatePositions(any()) }
    }

    @Test
    fun applySongOrderAppendsSongsMissingFromTheRequestedOrder() = runTest {
        givenStoredSongs("a" to 0, "b" to 1, "fresh" to 2)

        val changed = repository.applySongOrder(PLAYLIST_ID, listOf("b", "a"))

        assertTrue(changed)
        assertEquals(listOf("b", "a", "fresh"), resultingOrder("a" to 0, "b" to 1, "fresh" to 2))
    }

    @Test
    fun applySongOrderIgnoresUnknownIdsAndDuplicates() = runTest {
        givenStoredSongs("a" to 0, "b" to 1)

        repository.applySongOrder(PLAYLIST_ID, listOf("b", "b", "ghost", "a"))

        assertEquals(listOf("b", "a"), writtenIds())
        assertEquals(listOf(0, 1), writtenPositions())
    }

    @Test
    fun applySongOrderRunsInsideASingleTransaction() = runTest {
        givenStoredSongs("a" to 0, "b" to 1)

        repository.applySongOrder(PLAYLIST_ID, listOf("b", "a"))

        assertEquals(1, transactionCount)
    }

    @Test
    fun applySongOrderSkipsWriteForSingleTrackPlaylist() = runTest {
        givenStoredSongs("a" to 0)

        assertFalse(repository.applySongOrder(PLAYLIST_ID, listOf("a")))
        coVerify(exactly = 0) { songDao.updatePositions(any()) }
    }

    private fun givenStoredSongs(vararg songs: Pair<String, Int>) {
        val stored = songs.map { (id, position) -> song(id, position) }
        coEvery { songDao.getSongsForPlaylist(PLAYLIST_ID) } returns stored
    }

    private fun writtenIds(): List<String> = written.flatten().map { it.id }

    private fun writtenPositions(): List<Int> = written.flatten().map { it.position }

    /**
     * The order the `songs` table ends up in once [written] is applied to [stored].
     */
    private fun resultingOrder(vararg stored: Pair<String, Int>): List<String> {
        val positions = stored.associate { (id, position) -> id to position }.toMutableMap()
        written.flatten().forEach { positions[it.id] = it.position }
        return positions.entries.sortedBy { it.value }.map { it.key }
    }

    private fun song(id: String, position: Int) = SongEntity(
        id = id,
        playlistId = PLAYLIST_ID,
        spotifyId = "spotify-$id",
        title = "title-$id",
        artists = "artist",
        position = position,
    )

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
    }
}
