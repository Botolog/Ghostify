package com.ghostify.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle

/**
 * T-109: Library exposes playlists sorted by creation date (newest first).
 * T-112: Loading / Empty / Error states exposed correctly.
 * T-113: Config change (rotation) → VM survives, UI re-renders from state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest : ViewModelTest() {

    @Test
    fun `T-109 playlists are sorted by creation date, newest first`() = runVmTest {
        val repo = FakePlaylistRepository()
        repo.playlists.value = listOf(
            playlist("old", createdAt = 1_000L),
            playlist("newest", createdAt = 3_000L),
            playlist("middle", createdAt = 2_000L),
        )
        val vm = LibraryViewModel(repo)

        val states = mutableListOf<LibraryUiState>()
        val collector = launch { vm.uiState.collect { states += it } }
        advanceUntilIdle()

        val content = assertIs<LibraryUiState.Content>(vm.uiState.value)
        assertEquals(listOf("newest", "middle", "old"), content.playlists.map { it.id })
        assertTrue(states.last() is LibraryUiState.Content)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-112 library exposes Loading then Empty for a fresh library`() = runVmTest {
        val vm = LibraryViewModel(FakePlaylistRepository())

        // Before the data source is observed, the screen exposes Loading.
        assertIs<LibraryUiState.Loading>(vm.uiState.value)

        val states = mutableListOf<LibraryUiState>()
        val collector = launch { vm.uiState.collect { states += it } }
        advanceUntilIdle()

        // Once the (empty) data source is observed, the screen exposes Empty.
        assertIs<LibraryUiState.Empty>(vm.uiState.value)
        assertTrue(states.isNotEmpty(), "collector must observe the terminal state")
        assertIs<LibraryUiState.Empty>(states.last())
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-112 library exposes Error when the data source fails`() = runVmTest {
        val repo = FakePlaylistRepository().apply { failObserve = true }
        val vm = LibraryViewModel(repo)

        val states = mutableListOf<LibraryUiState>()
        val collector = launch { vm.uiState.collect { states += it } }
        advanceUntilIdle()

        val error = assertIs<LibraryUiState.Error>(vm.uiState.value)
        assertEquals("boom", error.message)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-112 library exposes Content with playlists`() = runVmTest {
        val repo = FakePlaylistRepository()
        repo.playlists.value = listOf(playlist("p1"))
        val vm = LibraryViewModel(repo)

        val collector = launch { vm.uiState.collect { } }
        advanceUntilIdle()

        val content = assertIs<LibraryUiState.Content>(vm.uiState.value)
        assertEquals(1, content.playlists.size)
        vm.clear()
        collector.cancel()
    }

    @Test
    fun `T-113 rotation keeps the VM alive and re-renders from cached state`() = runVmTest {
        val repo = FakePlaylistRepository()
        repo.playlists.value = listOf(playlist("p1", createdAt = 1_000L))
        val vm = LibraryViewModel(repo)

        // First "screen" renders Content.
        val firstView = mutableListOf<LibraryUiState>()
        val first = launch { vm.uiState.collect { firstView += it } }
        advanceUntilIdle()
        assertIs<LibraryUiState.Content>(vm.uiState.value)

        // Rotation: the old view is disposed...
        first.cancel()
        advanceUntilIdle()

        // ...but the ViewModel instance (and its scope) survives — the flow it
        // subscribes to is still active (not cancelled with the view).
        assertTrue(vm.viewModelScope.isActive, "VM must survive rotation")

        // The re-created UI collects the same StateFlow and immediately sees
        // the latest cached state — no refetch, no Loading flicker.
        val secondView = mutableListOf<LibraryUiState>()
        val second = launch { vm.uiState.collect { secondView += it } }
        advanceUntilIdle()

        assertEquals(1, secondView.size, "re-created view re-renders from cached state directly")
        assertIs<LibraryUiState.Content>(secondView.single())

        vm.clear()
        second.cancel()
    }
}
