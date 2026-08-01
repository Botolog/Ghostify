package com.ghostify.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ghostify.ui.contract.LibraryContract
import com.ghostify.ui.contract.PlaylistDetailContract
import com.ghostify.ui.contract.SettingsContract
import com.ghostify.ui.library.LibraryTestTags
import com.ghostify.ui.playlist.PlaylistDetailTestTags
import com.ghostify.ui.settings.SettingsTestTags
import org.junit.Rule
import org.junit.Test

/**
 * Full-screen navigation + wiring tests through the real NavHost with fake contracts.
 *
 * Covers the cross-screen behaviors that single-screen tests can't: navigation on tap
 * (T-122), deep-link cold start straight into the player (T-143), and clear-cache not
 * touching the library (T-149).
 */
class GhostifyAppTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun t122_tapPlaylistNavigatesToDetail() {
        val library = FakeLibraryContract(
            LibraryContract.LibraryUiState(
                loading = false,
                playlists = listOf(Fixtures.playlist(id = "p1", name = "Hits")),
            ),
        )
        val detail = FakePlaylistDetailContract(
            PlaylistDetailContract.PlaylistDetailUiState(
                playlistId = "p1",
                name = "Hits",
                tracks = listOf(Fixtures.track(id = "t1", title = "Blinding Lights")),
                downloadedCount = 1,
                trackCount = 1,
                loading = false,
            ),
        )

        rule.setContent {
            GhostifyApp(
                deps = GhostifyDependencies(
                    library = library,
                    addPlaylist = FakeAddPlaylistContract(),
                    detailFor = { detail },
                    player = FakePlayerContract(),
                    settings = FakeSettingsContract(),
                ),
            )
        }

        rule.onNodeWithTag(LibraryTestTags.item("p1")).performClick()

        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Blinding Lights").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Blinding Lights").assertIsDisplayed()
        rule.onNodeWithTag(PlaylistDetailTestTags.TRACK_LIST).assertIsDisplayed()
    }

    @Test
    fun t143_playerShowsEvenWhenLaunchedFromNotification() {
        val player = FakePlayerContract(
            Fixtures.playerState(title = "Notification Song", durationMs = 200_000, queue = emptyList()),
        )
        // Simulate a cold start deep-linked to the player (the app root maps a
        // "ghostify://player" notification intent to Routes.PLAYER).
        rule.setContent {
            GhostifyApp(
                deps = GhostifyDependencies(
                    library = FakeLibraryContract(),
                    addPlaylist = FakeAddPlaylistContract(),
                    detailFor = { FakePlaylistDetailContract() },
                    player = player,
                    settings = FakeSettingsContract(),
                ),
                initialRoute = Routes.PLAYER,
            )
        }

        rule.onNodeWithTag(com.ghostify.ui.player.PlayerTestTags.PLAY).assertIsDisplayed()
        rule.onNodeWithText("Notification Song").assertIsDisplayed()
    }

    @Test
    fun t143b_launchedFromNotificationWithEmptyQueueShowsNothingPlaying() {
        rule.setContent {
            GhostifyApp(
                deps = GhostifyDependencies(
                    library = FakeLibraryContract(),
                    addPlaylist = FakeAddPlaylistContract(),
                    detailFor = { FakePlaylistDetailContract() },
                    player = FakePlayerContract(),
                    settings = FakeSettingsContract(),
                ),
                initialRoute = Routes.PLAYER,
            )
        }

        rule.onNodeWithText("Nothing playing").assertIsDisplayed()
    }

    @Test
    fun t149_clearCacheKeepsLibraryRecords() {
        val library = FakeLibraryContract(
            LibraryContract.LibraryUiState(
                loading = false,
                playlists = listOf(Fixtures.playlist(id = "p1", name = "Hits")),
            ),
        )
        val settings = FakeSettingsContract(
            SettingsContract.SettingsUiState(
                storagePath = "/sdcard/Ghostify",
                cacheStats = com.ghostify.ui.model.CacheStats(fileCount = 9, sizeBytes = 0),
            ),
        )

        rule.setContent {
            GhostifyApp(
                deps = GhostifyDependencies(
                    library = library,
                    addPlaylist = FakeAddPlaylistContract(),
                    detailFor = { FakePlaylistDetailContract() },
                    player = FakePlayerContract(),
                    settings = settings,
                ),
            )
        }

        // Open settings.
        rule.onNodeWithTag(LibraryTestTags.SETTINGS).performClick()
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("9 files · 0 KB").fetchSemanticsNodes().isNotEmpty()
        }

        // Clear cache → file count drops to zero.
        rule.onNodeWithTag(SettingsTestTags.CLEAR_CACHE).performClick()
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("0 files · 0 KB").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("0 files · 0 KB").assertIsDisplayed()
        assert(settings.clearCacheClicks.isNotEmpty())

        // Navigate back — the library record still exists.
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Hits").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Hits").assertIsDisplayed()
        assert(library.state.value.playlists.size == 1)
    }
}
