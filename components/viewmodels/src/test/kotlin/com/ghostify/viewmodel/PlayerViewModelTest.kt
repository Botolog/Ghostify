package com.ghostify.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle

/**
 * T-111: Player VM maps player state → UI state (playing / position / shuffle /
 * repeat) without leaks.
 * T-112: Loading / NothingToPlay / Error states exposed correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest : ViewModelTest() {

    private fun playingState() = PlayerPlaybackState(
        isPlaying = true,
        positionMs = 42_000L,
        durationMs = 180_000L,
        shuffle = true,
        repeat = RepeatMode.ALL,
        currentTrackId = "t1",
        currentTrackTitle = "Song One",
        currentTrackArtist = "Artist A",
        queueSize = 3,
    )

    @Test
    fun `T-111 maps player state to UI state (playing, position, shuffle, repeat)`() = runVmTest {
        val player = FakePlayerController()
        player.state.value = playingState()
        val vm = PlayerViewModel(player)

        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()

        val content = assertIs<PlayerUiState.Content>(vm.uiState.value)
        assertEquals("Song One", content.title)
        assertEquals("Artist A", content.artist)
        assertTrue(content.isPlaying)
        assertEquals(42_000L, content.positionMs)
        assertEquals(180_000L, content.durationMs)
        assertTrue(content.shuffle)
        assertEquals(RepeatMode.ALL, content.repeat)
        assertEquals(3, content.queueSize)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-111 commands are forwarded to the controller`() = runVmTest {
        val player = FakePlayerController()
        val vm = PlayerViewModel(player)
        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.togglePlayPause()
        advanceUntilIdle()
        assertIs<PlayerUiState.Content>(vm.uiState.value)
        assertTrue(player.state.value.isPlaying, "play/pause toggled the player")

        vm.seekTo(9_000L)
        vm.setShuffle(true)
        vm.setRepeat(RepeatMode.ONE)
        advanceUntilIdle()

        assertEquals(9_000L, player.state.value.positionMs)
        assertTrue(player.state.value.shuffle)
        assertEquals(RepeatMode.ONE, player.state.value.repeat)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-111 no leak when the VM is cleared the state subscription ends`() = runVmTest {
        val player = FakePlayerController()
        val vm = PlayerViewModel(player)
        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()

        assertEquals(1, player.observers, "exactly one active subscription while alive")

        vm.clear()
        collector.cancel()
        advanceUntilIdle()

        assertEquals(0, player.observers, "subscription must be released on clear")
    }

    @Test
    fun `T-112 player exposes Loading initially`() = runVmTest {
        val vm = PlayerViewModel(FakePlayerController())
        assertIs<PlayerUiState.Loading>(vm.uiState.value)
        vm.clear()
    }

    @Test
    fun `T-112 player exposes NothingToPlay for an empty queue`() = runVmTest {
        val player = FakePlayerController()
        player.state.value = PlayerPlaybackState(nothingToPlay = true)
        val vm = PlayerViewModel(player)
        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertIs<PlayerUiState.NothingToPlay>(vm.uiState.value)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-112 player exposes Error carried by the player state`() = runVmTest {
        val player = FakePlayerController()
        player.state.value = PlayerPlaybackState(error = "Corrupt file skipped")
        val vm = PlayerViewModel(player)
        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()
        val error = assertIs<PlayerUiState.Error>(vm.uiState.value)
        assertEquals("Corrupt file skipped", error.message)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-112 loadPlaylist with only non-downloaded tracks becomes NothingToPlay`() = runVmTest {
        val player = FakePlayerController()
        val vm = PlayerViewModel(player)
        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.loadPlaylist("pl-1", listOf(track("t1", "pl-1", status = SongStatus.PENDING)))
        advanceUntilIdle()

        // Only DOWNLOADED tracks may enter the queue → empty queue → nothing to play.
        assertEquals(0, player.loaded.last().second)
        assertIs<PlayerUiState.NothingToPlay>(vm.uiState.value)
        vm.clear()
        collector.cancel()
    }
}
