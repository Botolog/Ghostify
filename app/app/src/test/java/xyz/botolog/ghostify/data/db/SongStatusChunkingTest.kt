package xyz.botolog.ghostify.data.db

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.dao.forEachIdChunk
import xyz.botolog.ghostify.data.db.dao.updateStatusInChunks
import xyz.botolog.ghostify.data.model.SongStatus
import xyz.botolog.ghostify.data.repo.SongRepository

/**
 * Guards the `IN (...)` chunking that keeps a 1370-track "download all" from
 * exceeding SQLite's `SQLITE_MAX_VARIABLE_NUMBER` (~999).
 */
class SongStatusChunkingTest {

    @Test
    fun `updateStatusInChunks splits 1370 ids into batches under the variable limit`() = runTest {
        val ids = (1..1370).map { "id-$it" }
        val batches = mutableListOf<List<String>>()

        updateStatusInChunks(ids, SongStatus.QUEUED) { chunk, _ -> batches += chunk }

        assertEquals(2, batches.size)
        assertTrue("every batch must respect the SQLite limit", batches.all { it.size <= SongDao.UPDATE_STATUS_CHUNK_SIZE })
        assertTrue("every batch must be <= 900", batches.all { it.size <= 900 })
        assertEquals("every id must be covered exactly once, in order", ids, batches.flatten())
    }

    @Test
    fun `updateStatusInChunks issues nothing for an empty id list`() = runTest {
        var calls = 0

        updateStatusInChunks(emptyList(), SongStatus.QUEUED) { _, _ -> calls += 1 }

        assertEquals(0, calls)
    }

    @Test
    fun `updateStatusInChunks keeps a single batch at the boundary`() = runTest {
        val ids = (1..900).map { "id-$it" }
        val batches = mutableListOf<List<String>>()

        updateStatusInChunks(ids, SongStatus.PENDING) { chunk, _ -> batches += chunk }

        assertEquals(listOf(ids), batches)
    }

    @Test
    fun `updateStatusInChunks splits one id over the boundary into two batches`() = runTest {
        val ids = (1..901).map { "id-$it" }
        val batches = mutableListOf<List<String>>()

        updateStatusInChunks(ids, SongStatus.DOWNLOADING) { chunk, _ -> batches += chunk }

        assertEquals(listOf(900, 1), batches.map { it.size })
        assertEquals(ids, batches.flatten())
    }

    @Test
    fun `SongRepository setStatus chunks a 1370-id call inside one transaction`() = runTest {
        val ids = (1..1370).map { "id-$it" }
        val batches = mutableListOf<List<String>>()
        val statuses = mutableListOf<SongStatus>()
        var transactionCount = 0
        val dao = mockk<SongDao>(relaxed = true)
        coEvery { dao.updateStatus(any(), any()) } answers {
            batches += firstArg<List<String>>()
            statuses += secondArg<SongStatus>()
        }
        val repository = SongRepository(
            songDao = dao,
            transactions = object : TransactionRunner {
                override suspend fun <R> withinTransaction(block: suspend () -> R): R {
                    transactionCount++
                    return block()
                }
            },
        )

        repository.setStatus(ids, SongStatus.QUEUED)

        assertEquals("the whole chunked write must be one transaction", 1, transactionCount)
        assertEquals(2, batches.size)
        assertTrue("every DAO call must stay under the limit", batches.all { it.size <= SongDao.UPDATE_STATUS_CHUNK_SIZE })
        assertEquals(ids, batches.flatten())
        assertTrue(statuses.all { it == SongStatus.QUEUED })
    }

    @Test
    fun `SongRepository setStatus writes nothing for an empty id list`() = runTest {
        var calls = 0
        var transactionCount = 0
        val dao = mockk<SongDao>(relaxed = true)
        coEvery { dao.updateStatus(any(), any()) } answers { calls += 1 }
        val repository = SongRepository(
            songDao = dao,
            transactions = object : TransactionRunner {
                override suspend fun <R> withinTransaction(block: suspend () -> R): R {
                    transactionCount++
                    return block()
                }
            },
        )

        repository.setStatus(emptyList(), SongStatus.QUEUED)

        assertEquals(0, calls)
        assertEquals("an empty write must not even open a transaction", 0, transactionCount)
    }

    // ── forEachIdChunk (the shared chunker behind status + delete) ──────────

    @Test
    fun `forEachIdChunk splits one id over the boundary into two batches`() = runTest {
        val ids = (1..901).map { "id-$it" }
        val batches = mutableListOf<List<String>>()

        forEachIdChunk(ids) { chunk -> batches += chunk }

        assertEquals(listOf(900, 1), batches.map { it.size })
        assertEquals(ids, batches.flatten())
    }

    @Test
    fun `forEachIdChunk issues nothing for an empty id list`() = runTest {
        var calls = 0

        forEachIdChunk(emptyList<String>()) { calls += 1 }

        assertEquals(0, calls)
    }

    // ── SongRepository.deleteBySpotifyIds routing ─────────────────────────

    @Test
    fun `SongRepository deleteBySpotifyIds routes through the chunked dao in one transaction`() = runTest {
        val dao = mockk<SongDao>(relaxed = true)
        var transactionCount = 0
        val repository = SongRepository(
            songDao = dao,
            transactions = object : TransactionRunner {
                override suspend fun <R> withinTransaction(block: suspend () -> R): R {
                    transactionCount++
                    return block()
                }
            },
        )

        repository.deleteBySpotifyIds("pl1", listOf("a", "b"))

        coVerify(exactly = 1) { dao.deleteBySpotifyIdsChunked("pl1", listOf("a", "b")) }
        coVerify(exactly = 0) { dao.deleteBySpotifyIds(any(), any()) }
        assertEquals("the chunked delete must be one transaction", 1, transactionCount)
    }

    @Test
    fun `SongRepository deleteBySpotifyIds writes nothing for an empty id list`() = runTest {
        val dao = mockk<SongDao>(relaxed = true)
        var transactionCount = 0
        val repository = SongRepository(
            songDao = dao,
            transactions = object : TransactionRunner {
                override suspend fun <R> withinTransaction(block: suspend () -> R): R {
                    transactionCount++
                    return block()
                }
            },
        )

        repository.deleteBySpotifyIds("pl1", emptyList())

        coVerify(exactly = 0) { dao.deleteBySpotifyIdsChunked(any(), any()) }
        coVerify(exactly = 0) { dao.deleteBySpotifyIds(any(), any()) }
        assertEquals("an empty delete must not even open a transaction", 0, transactionCount)
    }
}
