package com.ghostify.ui.player

import androidx.compose.ui.semantics.SemanticsActions
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
import com.ghostify.ui.util.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T-137..T-144 — Player screen.
 */
class PlayerScreenTest {

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
                PlayerScreen(contract = contract, onBack = {})
            }
        }
    }

    @Test
    fun t137_showsCoverTitleArtistAlbum() {
        val contract = FakePlayerContract(
            Fixtures.playerState(
                title = "Blinding Lights",
                durationMs = 200_000,
                queue = queue,
            ),
        )
        setContent(contract)

        contract.state.value = contract.state.value.copy(
            nowPlaying = Fixtures.nowPlaying("Blinding Lights", "The Weeknd", "After Hours"),
        )
        rule.onNodeWithTag(PlayerTestTags.COVER).assertIsDisplayed()
        rule.onNodeWithTag(PlayerTestTags.TITLE).assertIsDisplayed()
        rule.onNodeWithText("Blinding Lights").assertIsDisplayed()
        rule.onNodeWithText("The Weeknd").assertIsDisplayed()
        rule.onNodeWithText("After Hours").assertIsDisplayed()
    }

    @Test
    fun t138_progressBarReflectsPositionAndDragSeeks() {
        val contract = FakePlayerContract(
            Fixtures.playerState(positionMs = 30_000, durationMs = 180_000, queue = queue),
        )
        setContent(contract)

        // Position reflected as a formatted label under the seek bar.
        rule.onNodeWithText("0:30").assertIsDisplayed()

        // Drag (semantics SetProgress) seeks to ~50% of the duration.
        rule.onNodeWithTag(PlayerTestTags.SEEK_BAR)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }

        rule.waitUntil(timeoutMillis = 2_000) { contract.seeks.isNotEmpty() }
        assertTrue("expected a seek around 90s, got ${contract.seeks}", contract.seeks.any { it in 80_000..100_000 })
        rule.onNodeWithText("1:30").assertIsDisplayed()
    }

    @Test
    fun t139_playPauseNextPrevFunctional() {
        val contract = FakePlayerContract(
            Fixtures.playerState(isPlaying = false, queue = queue),
        )
        setContent(contract)

        rule.onNodeWithTag(PlayerTestTags.PLAY).performClick()
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        assertTrue(contract.calls.contains("togglePlay"))

        rule.onNodeWithTag(PlayerTestTags.NEXT).performClick()
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Song Two").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(contract.calls.contains("next"))

        rule.onNodeWithTag(PlayerTestTags.PREV).performClick()
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Song One").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(contract.calls.contains("previous"))
    }

    @Test
    fun t140_shuffleToggleReflectsState() {
        val contract = FakePlayerContract(Fixtures.playerState(queue = queue))
        setContent(contract)

        rule.onNodeWithTag(PlayerTestTags.SHUFFLE).assertIsOff()
        rule.onNodeWithTag(PlayerTestTags.SHUFFLE).performClick()
        rule.onNodeWithTag(PlayerTestTags.SHUFFLE).assertIsOn()
        rule.onNodeWithTag(PlayerTestTags.SHUFFLE).performClick()
        rule.onNodeWithTag(PlayerTestTags.SHUFFLE).assertIsOff()
        assertTrue(contract.calls.count { it == "shuffle" } == 2)
    }

    @Test
    fun t140b_repeatCyclesOffToAllToOne() {
        val contract = FakePlayerContract(Fixtures.playerState(queue = queue))
        setContent(contract)

        rule.onNodeWithTag(PlayerTestTags.REPEAT).assertIsOff()
        rule.onNodeWithContentDescription("Repeat: Off").assertIsDisplayed()

        rule.onNodeWithTag(PlayerTestTags.REPEAT).performClick()
        rule.onNodeWithTag(PlayerTestTags.REPEAT).assertIsOn()
        rule.onNodeWithContentDescription("Repeat: All").assertIsDisplayed()
        assertEquals(RepeatMode.ALL, contract.state.value.repeatMode)

        rule.onNodeWithTag(PlayerTestTags.REPEAT).performClick()
        rule.onNodeWithContentDescription("Repeat: One").assertIsDisplayed()
        assertEquals(RepeatMode.ONE, contract.state.value.repeatMode)

        rule.onNodeWithTag(PlayerTestTags.REPEAT).performClick()
        rule.onNodeWithContentDescription("Repeat: Off").assertIsDisplayed()
        assertEquals(RepeatMode.OFF, contract.state.value.repeatMode)
        rule.onNodeWithTag(PlayerTestTags.REPEAT).assertIsOff()
    }

    @Test
    fun t141_volumeSliderWorks() {
        val contract = FakePlayerContract(Fixtures.playerState(volume = 0.8f, queue = queue))
        setContent(contract)

        rule.onNodeWithText("80%").assertIsDisplayed()

        rule.onNodeWithTag(PlayerTestTags.VOLUME_SLIDER)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }

        rule.waitUntil(timeoutMillis = 2_000) { contract.volumes.isNotEmpty() }
        assertTrue(contract.volumes.any { it in 0.3f..0.5f })
        rule.onNodeWithText("40%").assertIsDisplayed()
    }

    @Test
    fun t142_queueDrawerListsUpcomingAndJumpWorks() {
        val contract = FakePlayerContract(Fixtures.playerState(queue = queue))
        setContent(contract)

        rule.onNodeWithTag(PlayerTestTags.QUEUE_BUTTON).performClick()
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Next up").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Song Two").assertIsDisplayed()
        rule.onNodeWithText("Song Three").assertIsDisplayed()

        rule.onNodeWithTag(PlayerTestTags.queueItem(2)).performClick()

        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Song Three").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Song Three").assertIsDisplayed()
        assertTrue(contract.calls.contains("jump:2"))
    }

    @Test
    fun t144_emptyQueueShowsNothingPlaying() {
        val contract = FakePlayerContract(PlayerContract.PlayerUiState(empty = true))
        setContent(contract)

        rule.onNodeWithTag(PlayerTestTags.EMPTY).assertIsDisplayed()
        rule.onNodeWithText("Nothing playing").assertIsDisplayed()
    }
}
