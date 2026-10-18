package xyz.botolog.ghostify.sync

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.TransactionRunner
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.model.PlaylistOrigin
import xyz.botolog.ghostify.data.model.PlaylistStatus
import xyz.botolog.ghostify.python.PlaylistMetadata

/**
 * JVM tests for the global sync status that [SyncUseCase] publishes to [SyncStatusHolder]
 * and persists through its `onSyncFinished` hook (the "Library" widget's readout).
 *
 * These pin the contract the widget relies on: the flag is up for the whole sync, a
 * successful sync stamps the finish time and invokes the hook, a failed sync still clears the
 * flag without invoking the hook, and a throwing hook never fails the sync itself.
 */
class SyncUseCaseStatusTest {

    private val playlistDao = mockk<PlaylistDao>()
    private val songDao = mockk<SongDao>()
    private val fetcher = mockk<SpotifyPlaylistFetcher>()

    @Before
    fun setUp() {
        SyncStatusHolder.reset()
    }

    @After
    fun tearDown() {
        SyncStatusHolder.reset()
    }

    private fun useCase(onSyncFinished: (suspend (Long) -> Unit)? = null): SyncUseCase = SyncUseCase(
        playlists = playlistDao,
        songs = songDao,
        transactions = DirectTransactions,
        fetcher = fetcher,
        files = NoFiles,
        enqueuer = DownloadEnqueuer { },
        locks = SyncLocks(),
        now = { NOW },
        newId = { "new" },
        onSyncFinished = onSyncFinished,
    )

    private fun stubStoredPlaylist() {
        coEvery { playlistDao.getById(PLAYLIST_ID) } returns PlaylistEntity(
            id = PLAYLIST_ID,
            spotifyId = SPOTIFY_ID,
            name = "Playlist",
            status = PlaylistStatus.NEW,
        )
        coEvery { playlistDao.update(any()) } returns Unit
        coEvery { songDao.getSongsForPlaylist(PLAYLIST_ID) } returns emptyList()
    }

    private fun stubRemotePlaylist() {
        coEvery { fetcher.fetchPlaylist(SPOTIFY_ID, PlaylistOrigin.SPOTIFY) } returns PlaylistMetadata(
            name = "Playlist",
            owner = "Owner",
            coverUrl = null,
            description = null,
            trackCount = 0,
            tracks = emptyList(),
        )
    }

    @Test
    fun successfulSyncStampsTheHolderAndInvokesTheHook() = runTest {
        stubStoredPlaylist()
        stubRemotePlaylist()
        val stamps = mutableListOf<Long>()

        useCase(onSyncFinished = { stamps += it }).syncPlaylist(PLAYLIST_ID)

        assertEquals(listOf(NOW), stamps)
        assertEquals(SyncStatus(isSyncing = false, lastSyncedAtMs = NOW), SyncStatusHolder.state.value)
    }

    @Test
    fun theFlagIsUpWhileTheSyncIsRunning() = runTest {
        stubStoredPlaylist()
        var observedWhileRunning = false
        coEvery { fetcher.fetchPlaylist(SPOTIFY_ID, PlaylistOrigin.SPOTIFY) } answers {
            observedWhileRunning = SyncStatusHolder.current.isSyncing
            PlaylistMetadata(
                name = "Playlist",
                owner = "Owner",
                coverUrl = null,
                description = null,
                trackCount = 0,
                tracks = emptyList(),
            )
        }

        useCase().syncPlaylist(PLAYLIST_ID)

        assertTrue("the flag must be visible to the widget during the sync", observedWhileRunning)
        assertFalse("and cleared afterwards", SyncStatusHolder.current.isSyncing)
    }

    @Test
    fun failedSyncClearsTheFlagAndSkipsTheHook() = runTest {
        stubStoredPlaylist()
        coEvery { fetcher.fetchPlaylist(SPOTIFY_ID, PlaylistOrigin.SPOTIFY) } throws RuntimeException("offline")
        val stamps = mutableListOf<Long>()

        runCatching { useCase(onSyncFinished = { stamps += it }).syncPlaylist(PLAYLIST_ID) }

        assertFalse("a failed sync must not leave the widget stuck on 'Syncing'", SyncStatusHolder.current.isSyncing)
        assertTrue("a failed sync must not be stamped as the last sync", stamps.isEmpty())
    }

    @Test
    fun aFailingHookDoesNotFailTheSync() = runTest {
        stubStoredPlaylist()
        stubRemotePlaylist()

        val result = useCase(onSyncFinished = { throw RuntimeException("db down") }).syncPlaylist(PLAYLIST_ID)

        assertEquals(0, result.added)
        assertEquals(NOW, SyncStatusHolder.current.lastSyncedAtMs)
    }

    private object DirectTransactions : TransactionRunner {
        override suspend fun <R> withinTransaction(block: suspend () -> R): R = block()
    }

    private object NoFiles : LocalFileStore {
        override fun exists(filePath: String?): Boolean = false
        override fun delete(filePath: String) = Unit
    }

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
        const val SPOTIFY_ID = "spotify-1"
        const val NOW = 1_000L
    }
}
