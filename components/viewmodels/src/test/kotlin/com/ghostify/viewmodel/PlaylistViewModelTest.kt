package com.ghostify.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle

/**
 * T-110: Playlist VM reflects download progress (per-song + overall).
 * T-112: Loading / Error / Empty / NotFound states exposed correctly.
 * T-116: Actions (play / sync / delete) mid-download are safe — no crashes.
 * T-117: Deleting during download cancels the run and leaves no orphan files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest : ViewModelTest() {

    private val playlistId = "pl-1"

    private fun seededRepo(
        tracks: List<Track> = listOf(track("t1", playlistId, position = 0)),
    ): FakePlaylistRepository {
        val repo = FakePlaylistRepository()
        repo.playlists.value = listOf(playlist(id = playlistId, trackCount = tracks.size))
        repo.trackStore.value = mapOf(playlistId to tracks)
        return repo
    }

    private fun vm(
        repo: FakePlaylistRepository,
        downloads: FakeDownloadController = FakeDownloadController(),
        syncer: FakeSyncer = FakeSyncer(),
        fileStore: FakeFileStore = FakeFileStore(),
        player: FakePlayerController = FakePlayerController(),
    ): Triple<PlaylistViewModel, FakeDownloadController, FakeFileStore> {
        val deleter = DeletePlaylistUseCase(downloads, repo, fileStore)
        return Triple(
            PlaylistViewModel(playlistId, repo, downloads, syncer, deleter, player),
            downloads,
            fileStore,
        )
    }

    @Test
    fun `T-110 download progress events surface as per-song and overall`() = runVmTest {
        val repo = seededRepo(
            listOf(track("t1", playlistId, position = 0), track("t2", playlistId, position = 1))
        )
        val (vm, downloads, _) = vm(repo)

        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()

        downloads.progress.value = mapOf(
            playlistId to DownloadProgress(
                playlistId = playlistId,
                state = DownloadRunState.RUNNING,
                total = 2,
                done = 1,
                overallPercent = 50f,
                perSong = mapOf("t1" to SongProgress("t1", SongStatus.DOWNLOADING, 0.5f)),
            )
        )
        advanceUntilIdle()

        val content = assertIs<PlaylistUiState.Content>(vm.uiState.value)
        assertEquals(50f, content.progress?.overallPercent)
        val t1 = content.tracks.first { it.id == "t1" }
        assertEquals(SongStatus.DOWNLOADING, t1.status)
        assertEquals(0.5f, t1.fraction)
        // Untouched track keeps its own status.
        assertEquals(SongStatus.PENDING, content.tracks.first { it.id == "t2" }.status)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-112 playlist exposes Loading initially`() = runVmTest {
        val (vm, _, _) = vm(seededRepo())
        assertIs<PlaylistUiState.Loading>(vm.uiState.value)
        vm.clear()
    }

    @Test
    fun `T-112 playlist exposes Empty when the playlist has no tracks`() = runVmTest {
        val repo = seededRepo(tracks = emptyList())
        val (vm, _, _) = vm(repo)
        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertIs<PlaylistUiState.Empty>(vm.uiState.value)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-112 playlist exposes NotFound when the playlist does not exist`() = runVmTest {
        val repo = FakePlaylistRepository() // nothing seeded
        val (vm, _, _) = vm(repo)
        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertIs<PlaylistUiState.NotFound>(vm.uiState.value)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-112 playlist exposes Error when the data source fails`() = runVmTest {
        val repo = seededRepo().apply { failObserve = true }
        val (vm, _, _) = vm(repo)
        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertIs<PlaylistUiState.Error>(vm.uiState.value)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-116 play and sync mid-download are safe and never crash`() = runVmTest {
        // A playlist whose tracks are mid-download.
        val repo = seededRepo(
            listOf(
                track("t1", playlistId, status = SongStatus.DOWNLOADING, position = 0),
                track("t2", playlistId, status = SongStatus.QUEUED, position = 1),
            )
        )
        val (vm, downloads, _) = vm(repo)

        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()
        downloads.downloadAll(playlistId) // kick off a run
        advanceUntilIdle()

        // play() with no DOWNLOADED track → controller reports NothingToPlay,
        // the screen stays in Content — no crash.
        vm.play()
        advanceUntilIdle()

        // sync() while downloading → succeeds, and the follow-up downloadAll is
        // a benign no-op because a run is already active.
        vm.sync()
        advanceUntilIdle()

        val content = assertIs<PlaylistUiState.Content>(vm.uiState.value)
        assertEquals(SongStatus.DOWNLOADING, content.tracks.first { it.id == "t1" }.status)
        assertTrue(downloads.running.contains(playlistId), "run is still active")
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-116 duplicate download-all while running is a benign no-op`() = runVmTest {
        val (vm, downloads, _) = vm(seededRepo())
        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()

        val first = downloads.downloadAll(playlistId)
        val second = vm.downloadAll()
        advanceUntilIdle()

        assertTrue(first)
        // The VM does not throw and the duplicate call stays safe (returns Unit).
        assertEquals(listOf(playlistId, playlistId), downloads.downloadAllCalls)
        assertIs<PlaylistUiState.Content>(vm.uiState.value)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-117 deleting mid-download cancels the run and leaves no orphan files`() = runVmTest {
        val path1 = "/storage/pl-1/t1.mp3"
        val path2 = "/storage/pl-1/t2.mp3"
        val repo = seededRepo(
            listOf(
                track("t1", playlistId, status = SongStatus.DOWNLOADED, filePath = path1, position = 0),
                track("t2", playlistId, status = SongStatus.DOWNLOADING, filePath = path2, position = 1),
            )
        )
        val (vm, downloads, fileStore) = vm(repo)
        fileStore.files[path1] = playlistId
        fileStore.files[path2] = playlistId
        downloads.downloadAll(playlistId) // active run
        downloads.progress.value = mapOf(
            playlistId to DownloadProgress(playlistId, DownloadRunState.RUNNING, 2, 1, 50f)
        )

        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertTrue(downloads.running.contains(playlistId))

        vm.delete()
        advanceUntilIdle()

        // Download run cancelled & cleaned.
        assertTrue(playlistId in downloads.cancelCalls)
        assertTrue(!downloads.running.contains(playlistId), "run must be cancelled")

        // No orphan files left on disk.
        assertEquals(emptyList(), fileStore.listFiles())
        assertEquals(setOf(path1, path2), fileStore.deleted.toSet())

        // Rows are gone and the screen reflects deletion.
        assertEquals(emptyList(), repo.playlists.value)
        assertNull(repo.playlist(playlistId))
        assertIs<PlaylistUiState.NotFound>(vm.uiState.value)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-117 deleting a playlist with no running download still cleans files`() = runVmTest {
        val path = "/storage/pl-1/t1.mp3"
        val repo = seededRepo(
            listOf(track("t1", playlistId, status = SongStatus.DOWNLOADED, filePath = path))
        )
        val (vm, _, fileStore) = vm(repo)
        fileStore.files[path] = playlistId

        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.delete()
        advanceUntilIdle()

        assertEquals(emptyList(), fileStore.listFiles())
        assertEquals(emptyList(), repo.playlists.value)
        assertIs<PlaylistUiState.NotFound>(vm.uiState.value)
        vm.clear()
        collector.cancel()
    }
}
