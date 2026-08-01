package com.ghostify.ui.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ghostify.ui.FakeAddPlaylistContract
import com.ghostify.ui.FakeLibraryContract
import com.ghostify.ui.Fixtures
import com.ghostify.ui.add.AddPlaylistDialog
import com.ghostify.ui.model.PlaylistStatus
import com.ghostify.ui.theme.GhostifyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T-118..T-123 — Library screen.
 */
class LibraryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(
        contract: FakeLibraryContract,
        onOpenPlaylist: (String) -> Unit = {},
        onOpenSettings: () -> Unit = {},
        addDialog: @androidx.compose.runtime.Composable () -> Unit = {},
        now: () -> Long = { 1_700_010_000_000L },
    ) {
        rule.setContent {
            GhostifyTheme {
                LibraryScreen(
                    contract = contract,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenSettings = onOpenSettings,
                    addDialog = addDialog,
                    now = now,
                )
            }
        }
    }

    @Test
    fun t118_emptyStateShowsNoPlaylistsMessage() {
        setContent(FakeLibraryContract(com.ghostify.ui.contract.LibraryContract.LibraryUiState(loading = false)))

        rule.onNodeWithText("No playlists yet").assertIsDisplayed()
        rule.onNodeWithTag(LibraryTestTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun t119_listRendersCoverNameAndTrackCount() {
        val contract = FakeLibraryContract(
            com.ghostify.ui.contract.LibraryContract.LibraryUiState(
                loading = false,
                playlists = listOf(
                    Fixtures.playlist(id = "p1", name = "Morning Mix", trackCount = 12),
                ),
            ),
        )
        setContent(contract)

        rule.onNodeWithText("Morning Mix").assertIsDisplayed()
        rule.onNodeWithText("12 tracks").assertIsDisplayed()
        rule.onNodeWithTag(LibraryTestTags.COVER).assertIsDisplayed()
        rule.onNodeWithTag(LibraryTestTags.item("p1")).assertIsDisplayed()
    }

    @Test
    fun t120_downloadProgressBadgeUpdatesLive() {
        val contract = FakeLibraryContract(
            com.ghostify.ui.contract.LibraryContract.LibraryUiState(
                loading = false,
                playlists = listOf(
                    Fixtures.playlist(
                        id = "p1",
                        name = "Downloading Mix",
                        trackCount = 10,
                        status = PlaylistStatus.DOWNLOADING,
                        progress = 40,
                    ),
                ),
            ),
        )
        setContent(contract)

        rule.onNodeWithTag(LibraryTestTags.BADGE).assertIsDisplayed()
        rule.onNodeWithText("40%").assertIsDisplayed()

        contract.state.value = com.ghostify.ui.contract.LibraryContract.LibraryUiState(
            loading = false,
            playlists = listOf(
                Fixtures.playlist(
                    id = "p1",
                    name = "Downloading Mix",
                    trackCount = 10,
                    status = PlaylistStatus.DOWNLOADING,
                    progress = 100,
                ),
            ),
        )

        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("100%").assertIsDisplayed()
    }

    @Test
    fun t121_fabOpensAddPlaylistDialog() {
        val library = FakeLibraryContract()
        val add = FakeAddPlaylistContract()
        setContent(
            contract = library,
            addDialog = { AddPlaylistDialog(contract = add) },
        )

        rule.onNodeWithTag(LibraryTestTags.FAB).performClick()

        rule.onNodeWithText("Add playlist").assertIsDisplayed()
        assertTrue(library.addClicks.isNotEmpty())
    }

    @Test
    fun t122_tappingPlaylistInvokesOpenNavigationCallback() {
        val contract = FakeLibraryContract(
            com.ghostify.ui.contract.LibraryContract.LibraryUiState(
                loading = false,
                playlists = listOf(Fixtures.playlist(id = "p42", name = "Hits")),
            ),
        )
        val opened = mutableListOf<String>()
        setContent(contract, onOpenPlaylist = { opened += it })

        rule.onNodeWithTag(LibraryTestTags.item("p42")).performClick()

        rule.waitUntil(timeoutMillis = 2_000) { opened.isNotEmpty() }
        assertTrue("expected p42 to be opened, was $opened", opened == listOf("p42"))
    }

    @Test
    fun t123_lastSyncedTimeDisplayedAndFormatted() {
        val now = 1_700_010_000_000L
        val contract = FakeLibraryContract(
            com.ghostify.ui.contract.LibraryContract.LibraryUiState(
                loading = false,
                playlists = listOf(
                    Fixtures.playlist(
                        id = "p1",
                        name = "Synced Mix",
                        lastSyncedAt = now - 2 * 3_600_000L, // 2 hours ago
                    ),
                ),
            ),
        )
        setContent(contract, now = { now })

        rule.onNodeWithText("Synced 2h ago").assertIsDisplayed()

        contract.state.value = com.ghostify.ui.contract.LibraryContract.LibraryUiState(
            loading = false,
            playlists = listOf(
                Fixtures.playlist(
                    id = "p1",
                    name = "Synced Mix",
                    lastSyncedAt = now - 5 * 60_000L, // 5 minutes ago
                ),
            ),
        )
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Synced 5m ago").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun t123b_neverSyncedPlaylistShowsNever() {
        val contract = FakeLibraryContract(
            com.ghostify.ui.contract.LibraryContract.LibraryUiState(
                loading = false,
                playlists = listOf(Fixtures.playlist(id = "p1", name = "Fresh", lastSyncedAt = null)),
            ),
        )
        setContent(contract)

        rule.onNodeWithText("Synced Never").assertIsDisplayed()
        rule.onAllNodesWithTag(LibraryTestTags.SYNC_TIME).assertCountEquals(1)
    }
}
