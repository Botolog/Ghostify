package com.ghostify.ui.add

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ghostify.ui.FakeAddPlaylistContract
import com.ghostify.ui.model.PlaylistPreview
import com.ghostify.ui.theme.GhostifyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T-124..T-129 — Add-playlist dialog.
 */
class AddPlaylistDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(contract: FakeAddPlaylistContract) {
        rule.setContent {
            GhostifyTheme {
                AddPlaylistDialog(contract = contract)
            }
        }
    }

    private fun setUrl(contract: FakeAddPlaylistContract, url: String) {
        rule.onNodeWithTag(AddPlaylistTestTags.URL_FIELD).performTextInput(url)
        contract.state.value = contract.state.value.copy(url = url)
    }

    @Test
    fun t124_invalidUrlShowsValidationError() {
        val contract = FakeAddPlaylistContract()
        setContent(contract)

        setUrl(contract, "this is not a spotify link")
        rule.onNodeWithTag(AddPlaylistTestTags.FETCH_BUTTON).performClick()

        rule.onNodeWithTag(AddPlaylistTestTags.URL_ERROR)
            .assertIsDisplayed()
        rule.onNodeWithText("That doesn't look like a Spotify or YouTube link").assertIsDisplayed()
        assertTrue(contract.fetchedIds.isEmpty())
    }

    @Test
    fun t125_fetchMetadataShowsLoadingSpinner() {
        val contract = FakeAddPlaylistContract()
        setContent(contract)

        setUrl(contract, "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        contract.state.value = contract.state.value.copy(isFetching = true)

        rule.onNodeWithTag(AddPlaylistTestTags.LOADING).assertIsDisplayed()
        rule.onNodeWithText("Fetching playlist metadata…").assertIsDisplayed()
    }

    @Test
    fun t126_fetchSuccessShowsPreviewBeforeSave() {
        val contract = FakeAddPlaylistContract()
        setContent(contract)

        contract.state.value = contract.state.value.copy(
            preview = PlaylistPreview(
                name = "Chill Hits",
                owner = "Spotify",
                coverUrl = null,
                trackCount = 50,
            ),
            canSave = true,
        )

        rule.onNodeWithTag(AddPlaylistTestTags.PREVIEW).assertIsDisplayed()
        rule.onNodeWithText("Chill Hits").assertIsDisplayed()
        rule.onNodeWithText("by Spotify · 50 tracks").assertIsDisplayed()
        rule.onNodeWithTag(AddPlaylistTestTags.SAVE_BUTTON).assertIsEnabled()
    }

    @Test
    fun t127_saveDisabledUntilFetchSucceeds() {
        val contract = FakeAddPlaylistContract()
        setContent(contract)

        rule.onNodeWithTag(AddPlaylistTestTags.SAVE_BUTTON).assertIsNotEnabled()

        setUrl(contract, "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        rule.onNodeWithTag(AddPlaylistTestTags.SAVE_BUTTON).assertIsNotEnabled()

        contract.state.value = contract.state.value.copy(
            isFetching = false,
            preview = PlaylistPreview("Chill Hits", "Spotify", null, 50),
            canSave = true,
        )
        rule.onNodeWithTag(AddPlaylistTestTags.SAVE_BUTTON).assertIsEnabled()
    }

    @Test
    fun t128_fetchErrorShowsReadableMessage() {
        val contract = FakeAddPlaylistContract()
        setContent(contract)

        contract.state.value = contract.state.value.copy(
            fetchError = "This playlist is private. Ghostify supports public playlists only.",
        )

        rule.onNodeWithTag(AddPlaylistTestTags.FETCH_ERROR).assertIsDisplayed()
        rule.onNodeWithText("This playlist is private. Ghostify supports public playlists only.")
            .assertIsDisplayed()
        rule.onNodeWithTag(AddPlaylistTestTags.SAVE_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun t129_duplicatePlaylistShowsWarningAndDoesNotDuplicate() {
        val contract = FakeAddPlaylistContract()
        setContent(contract)

        contract.state.value = contract.state.value.copy(
            preview = PlaylistPreview("Chill Hits", "Spotify", null, 50),
            duplicateWarning = "This playlist is already in your library.",
            canSave = true,
        )

        rule.onNodeWithTag(AddPlaylistTestTags.DUPLICATE_WARNING).assertIsDisplayed()
        rule.onNodeWithText("This playlist is already in your library.").assertIsDisplayed()

        rule.onNodeWithTag(AddPlaylistTestTags.SAVE_BUTTON).performClick()

        // The dialog never performs a duplicate insert: no second fetch was triggered and
        // the contract only saw one save attempt. De-duplication (by spotify_id) is enforced
        // by the contract implementation, not the screen.
        assertTrue(contract.fetchedIds.isEmpty())
        assertEquals(1, contract.saveClicks.size)
    }
}
