package com.ghostify.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle

/**
 * T-112: Loading / Fetching / Error states exposed for the add-playlist screen.
 * T-114: Invalid URL → error message shown, dialog stays open.
 * T-115: Success → dialog closes, new playlist appears in the Library.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddPlaylistViewModelTest : ViewModelTest() {

    @Test
    fun `T-114 invalid URL shows the message and keeps the dialog open`() = runVmTest {
        val parser = FakeUrlParser().apply {
            result = UrlParseResult.Rejected("That link isn't a Spotify playlist.")
        }
        val vm = AddPlaylistViewModel(parser, FakeFetcher(), FakePlaylistRepository())

        vm.onUrlChange("https://youtube.com/watch?v=xyz")
        vm.submit()

        val state = assertIs<AddPlaylistUiState.Error>(vm.uiState.value)
        assertEquals("That link isn't a Spotify playlist.", state.message)
        assertTrue(state.dialogOpen, "dialog must stay open on error")
        vm.clear()
    }

    @Test
    fun `T-112 transient Fetching state is exposed while the fetch is in flight`() = runVmTest {
        val fetcher = FakeFetcher().apply { gate = CompletableDeferred() }
        val vm = AddPlaylistViewModel(FakeUrlParser(), fetcher, FakePlaylistRepository())

        vm.onUrlChange("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        vm.submit()
        advanceUntilIdle() // runs up to the gate

        assertIs<AddPlaylistUiState.Fetching>(vm.uiState.value)
        assertEquals(listOf(VALID_SPOTIFY_ID), fetcher.calls)

        fetcher.gate!!.complete(Unit)
        advanceUntilIdle()
        assertIs<AddPlaylistUiState.Preview>(vm.uiState.value)
        vm.clear()
    }

    @Test
    fun `T-112 fetch failure shows the message with retry hint and keeps dialog open`() = runVmTest {
        val fetcher = FakeFetcher().apply {
            result = PlaylistFetchResult.Failure("Network unreachable", "Check your connection.")
        }
        val vm = AddPlaylistViewModel(FakeUrlParser(), fetcher, FakePlaylistRepository())

        vm.onUrlChange("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        vm.submit()
        advanceUntilIdle()

        val state = assertIs<AddPlaylistUiState.Error>(vm.uiState.value)
        assertEquals("Network unreachable", state.message)
        assertEquals("Check your connection.", state.retryHint)
        assertTrue(state.dialogOpen)
        vm.clear()
    }

    @Test
    fun `T-112 editing the URL clears a previous error`() = runVmTest {
        val parser = FakeUrlParser().apply { result = UrlParseResult.Rejected("Bad link") }
        val vm = AddPlaylistViewModel(parser, FakeFetcher(), FakePlaylistRepository())

        vm.onUrlChange("garbage")
        vm.submit()
        assertIs<AddPlaylistUiState.Error>(vm.uiState.value)

        vm.onUrlChange("https://open.spotify.com/playlist/abc123")
        assertIs<AddPlaylistUiState.Idle>(vm.uiState.value)
        vm.clear()
    }

    @Test
    fun `T-115 success closes the dialog and the new playlist appears in the Library`() = runVmTest {
        val repo = FakePlaylistRepository()
        val fetcher = FakeFetcher().apply { result = PlaylistFetchResult.Success(sampleMetadata("Chill Hits")) }
        val vm = AddPlaylistViewModel(FakeUrlParser(), fetcher, repo)

        vm.onUrlChange("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        vm.submit()
        advanceUntilIdle()
        assertIs<AddPlaylistUiState.Preview>(vm.uiState.value)

        vm.save()
        advanceUntilIdle()

        // Dialog closed.
        val final = assertIs<AddPlaylistUiState.Success>(vm.uiState.value)
        assertFalse(final.dialogOpen, "dialog closes on success")

        // Persisted through the shared repository...
        assertEquals(1, repo.saved.size)
        val (savedPlaylist, savedTracks) = repo.saved.single()
        assertEquals("Chill Hits", savedPlaylist.name)
        assertEquals(VALID_SPOTIFY_ID, savedPlaylist.spotifyId)
        assertEquals(2, savedTracks.size)
        assertTrue(savedTracks.all { it.status == SongStatus.PENDING })

        // ...so the Library immediately shows it (T-115).
        val library = LibraryViewModel(repo)
        val collector = launch { library.uiState.collect { } }
        advanceUntilIdle()
        val content = assertIs<LibraryUiState.Content>(library.uiState.value)
        assertEquals(listOf("Chill Hits"), content.playlists.map { it.name })
        library.clear()
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-115 save failure keeps the dialog open with an error`() = runVmTest {
        val repo = object : FakePlaylistRepository() {
            override suspend fun save(playlist: PlaylistSummary, tracks: List<Track>): String =
                throw IllegalStateException("disk full")
        }
        val vm = AddPlaylistViewModel(FakeUrlParser(), FakeFetcher(), repo)

        vm.onUrlChange("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        vm.submit()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        val state = assertIs<AddPlaylistUiState.Error>(vm.uiState.value)
        assertEquals("disk full", state.message)
        assertTrue(state.dialogOpen)
        vm.clear()
    }
}
