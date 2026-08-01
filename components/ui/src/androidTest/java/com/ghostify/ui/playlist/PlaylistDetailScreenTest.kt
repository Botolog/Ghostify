package com.ghostify.ui.playlist

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ghostify.ui.FakePlaylistDetailContract
import com.ghostify.ui.Fixtures
import com.ghostify.ui.contract.PlaylistDetailContract
import com.ghostify.ui.model.SongStatus
import com.ghostify.ui.model.TrackUi
import com.ghostify.ui.theme.GhostifyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T-130..T-136 — Playlist detail screen.
 */
class PlaylistDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun stateOf(
        tracks: List<TrackUi>,
        downloaded: Int = tracks.count { it.status == SongStatus.DOWNLOADED },
        name: String = "Hits",
        isDownloadingAll: Boolean = false,
        downloadAllProgress: Int? = null,
        isSyncing: Boolean = false,
    ) = PlaylistDetailContract.PlaylistDetailUiState(
        playlistId = "p1",
        name = name,
        coverUrl = null,
        tracks = tracks,
        downloadedCount = downloaded,
        trackCount = tracks.size,
        loading = false,
        isDownloadingAll = isDownloadingAll,
        downloadAllProgress = downloadAllProgress,
        isSyncing = isSyncing,
    )

    private fun setContent(contract: FakePlaylistDetailContract) {
        rule.setContent {
            GhostifyTheme {
                PlaylistDetailScreen(contract = contract, onBack = {})
            }
        }
    }

    private val tracks = listOf(
        Fixtures.track(id = "t1", title = "Blinding Lights", artist = "The Weeknd", durationMs = 200_000),
        Fixtures.track(id = "t2", title = "Levitating", artist = "Dua Lipa", durationMs = 203_000),
        Fixtures.track(id = "t3", title = "Old Track", artist = "Artist", durationMs = 210_000),
    )

    @Test
    fun t130_trackListRendersTitleArtistDurationAndStatusIcon() {
        setContent(FakePlaylistDetailContract(stateOf(tracks)))

        rule.onNodeWithText("Blinding Lights").assertIsDisplayed()
        rule.onNodeWithText("The Weeknd").assertIsDisplayed()
        rule.onNodeWithText("3:20").assertIsDisplayed()
        rule.onNodeWithTag(PlaylistDetailTestTags.trackStatus(SongStatus.DOWNLOADED))
            .assertIsDisplayed()
        rule.onNodeWithTag(PlaylistDetailTestTags.track("t1")).assertIsDisplayed()
    }

    @Test
    fun t131_downloadAllStartsQueueAndShowsProgress() {
        val contract = FakePlaylistDetailContract(stateOf(tracks, downloaded = 0))
        setContent(contract)

        rule.onNodeWithTag(PlaylistDetailTestTags.DOWNLOAD_ALL).performClick()
        assertTrue(contract.downloadAllClicks.isNotEmpty())

        contract.state.value = contract.state.value.copy(
            isDownloadingAll = true,
            downloadAllProgress = 55,
        )
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Downloading 55%").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Downloading 55%").assertIsDisplayed()
        rule.onNodeWithTag(PlaylistDetailTestTags.DOWNLOAD_PROGRESS).assertIsDisplayed()
    }

    @Test
    fun t132_resyncTriggersDiffAndShowsSyncing() {
        val contract = FakePlaylistDetailContract(stateOf(tracks))
        setContent(contract)

        rule.onNodeWithTag(PlaylistDetailTestTags.RESYNC).performClick()
        assertTrue(contract.syncClicks.isNotEmpty())

        contract.state.value = contract.state.value.copy(isSyncing = true)
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Syncing with Spotify…").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(PlaylistDetailTestTags.SYNCING).assertIsDisplayed()
        rule.onNodeWithTag(PlaylistDetailTestTags.RESYNC).assertIsDisplayed()
        rule.onNodeWithText("Syncing…").assertIsDisplayed()
    }

    @Test
    fun t133_playAllDisabledWithHintWhenNoDownloadedTracks() {
        setContent(FakePlaylistDetailContract(stateOf(tracks, downloaded = 0)))

        rule.onNodeWithTag(PlaylistDetailTestTags.PLAY_ALL).assertIsNotEnabled()
        rule.onNodeWithText("No downloaded tracks yet — download some to play.").assertIsDisplayed()
        rule.onNodeWithTag(PlaylistDetailTestTags.PLAY_ALL_HINT).assertIsDisplayed()
    }

    @Test
    fun t133b_playAllEnabledWhenDownloadsExist() {
        val contract = FakePlaylistDetailContract(stateOf(tracks, downloaded = 3))
        setContent(contract)

        rule.onNodeWithTag(PlaylistDetailTestTags.PLAY_ALL).performClick()
        assertTrue(contract.playAllClicks.isNotEmpty())
    }

    @Test
    fun t134_longPressTrackRedownloadsSingleTrack() {
        val contract = FakePlaylistDetailContract(stateOf(tracks))
        setContent(contract)

        rule.onNodeWithTag(PlaylistDetailTestTags.track("t2")).performTouchInput { longClick() }

        rule.waitUntil(timeoutMillis = 2_000) { contract.retriedTracks.isNotEmpty() }
        assertTrue(contract.retriedTracks == listOf("t2"))
    }

    @Test
    fun t135_failedTracksVisuallyDistinctAndTapToRetry() {
        val state = stateOf(
            tracks = listOf(
                Fixtures.track(id = "f1", title = "Broken Song", status = SongStatus.FAILED),
                Fixtures.track(id = "ok", title = "Good Song", status = SongStatus.DOWNLOADED),
            ),
        )
        val contract = FakePlaylistDetailContract(state)
        setContent(contract)

        rule.onNodeWithText("Failed — tap to retry").assertIsDisplayed()
        rule.onNodeWithTag(PlaylistDetailTestTags.trackStatus(SongStatus.FAILED)).assertIsDisplayed()

        rule.onNodeWithTag(PlaylistDetailTestTags.track("f1")).performClick()

        rule.waitUntil(timeoutMillis = 2_000) { contract.retriedTracks.isNotEmpty() }
        assertTrue(contract.retriedTracks == listOf("f1"))
    }

    @Test
    fun t136_removedTrackDisappearsAfterSync() {
        val contract = FakePlaylistDetailContract(stateOf(tracks))
        setContent(contract)

        rule.onNodeWithText("Old Track").assertIsDisplayed()

        rule.onNodeWithTag(PlaylistDetailTestTags.RESYNC).performClick()
        assertTrue(contract.syncClicks.isNotEmpty())

        // Simulate the diff result: "Old Track" was removed on Spotify, so the contract drops it.
        contract.state.value = stateOf(tracks.filter { it.title != "Old Track" })

        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Old Track").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("Old Track").assertDoesNotExist()
        rule.onNodeWithText("Blinding Lights").assertIsDisplayed()
        rule.onNodeWithText("Levitating").assertIsDisplayed()
    }
}
