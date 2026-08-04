package com.ghostify.ui.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.ghostify.ui.FakePlayerContract
import com.ghostify.ui.Fixtures
import com.ghostify.ui.contract.PlayerContract
import com.ghostify.ui.theme.GhostifyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Persistent now-playing mini bar (Spotify-style).
 */
class MiniPlayerTest {

    @get:Rule
    val rule = createComposeRule()

    private val queue = listOf(
        Fixtures.queueItem("Song One", "Artist One").copy(isCurrent = true),
        Fixtures.queueItem("Song Two", "Artist Two"),
        Fixtures.queueItem("Song Three", "Artist Three"),
    )

    private fun setContent(contract: FakePlayerContract) {
        rule.setContent {
            GhostifyTheme {
                MiniPlayer(contract = contract)
            }
        }
    }

    @Test
    fun t201_showsCurrentTrackCoverAndControls() {
        val contract = FakePlayerContract(
            Fixtures.playerState(
                title = "Blinding Lights",
                isPlaying = false,
                queue = queue,
            ),
        )
        setContent(contract)

        rule.onNodeWithTag(MiniPlayerTestTags.BAR).assertIsDisplayed()
        rule.onNodeWithTag(MiniPlayerTestTags.COVER).assertIsDisplayed()
        rule.onNodeWithText("Blinding Lights").assertIsDisplayed()
        rule.onNodeWithText("Artist").assertIsDisplayed()
        rule.onNodeWithTag(MiniPlayerTestTags.PREV).assertIsDisplayed()
        rule.onNodeWithTag(MiniPlayerTestTags.PLAY).assertIsDisplayed()
        rule.onNodeWithTag(MiniPlayerTestTags.NEXT).assertIsDisplayed()
        rule.onNodeWithTag(MiniPlayerTestTags.SHUFFLE).assertIsDisplayed()
        rule.onNodeWithTag(MiniPlayerTestTags.SEEK).assertIsDisplayed()
    }

    @Test
    fun t202_hiddenWhenNothingLoaded() {
        setContent(FakePlayerContract(PlayerContract.PlayerUiState(empty = true)))

        rule.onNodeWithTag(MiniPlayerTestTags.BAR).assertDoesNotExist()
    }

    @Test
    fun t203_playPauseNextPrevFunctional() {
        val contract = FakePlayerContract(
            Fixtures.playerState(isPlaying = false, queue = queue),
        )
        setContent(contract)

        rule.onNodeWithTag(MiniPlayerTestTags.PLAY).performClick()
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        assertTrue(contract.calls.contains("togglePlay"))

        rule.onNodeWithTag(MiniPlayerTestTags.NEXT).performClick()
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Song Two").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(contract.calls.contains("next"))

        rule.onNodeWithTag(MiniPlayerTestTags.PREV).performClick()
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Song One").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(contract.calls.contains("previous"))
    }

    @Test
    fun t204_seekDragChangesTimestamp() {
        val contract = FakePlayerContract(
            Fixtures.playerState(positionMs = 30_000, durationMs = 180_000, queue = queue),
        )
        setContent(contract)

        // Drag the thin seek strip to ~50% of the duration.
        rule.onNodeWithTag(MiniPlayerTestTags.SEEK)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }

        rule.waitUntil(timeoutMillis = 2_000) { contract.seeks.isNotEmpty() }
        assertTrue(
            "expected a seek around 90s, got ${contract.seeks}",
            contract.seeks.any { it in 80_000..100_000 },
        )
    }

    @Test
    fun t205_shuffleToggleReflectsState() {
        val contract = FakePlayerContract(Fixtures.playerState(queue = queue))
        setContent(contract)

        rule.onNodeWithTag(MiniPlayerTestTags.SHUFFLE).assertIsOff()
        rule.onNodeWithTag(MiniPlayerTestTags.SHUFFLE).performClick()
        rule.onNodeWithTag(MiniPlayerTestTags.SHUFFLE).assertIsOn()
        assertTrue(contract.calls.contains("shuffle"))
    }
}
