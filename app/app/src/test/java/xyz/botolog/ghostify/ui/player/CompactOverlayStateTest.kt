package xyz.botolog.ghostify.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Compact playback overlay is transient UI, so the rules that decide whether it is up, when
 * its countdown restarts and what a tap on the cover art means are the whole behaviour — and all
 * of them are pure functions of the overlay's own state. That is what these tests pin down.
 */
class CompactOverlayStateTest {

    // ── Opening the screen ────────────────────────────────────────────────────

    @Test
    fun theOverlayStartsHidden() {
        assertFalse(CompactOverlay.hidden().visible)
    }

    @Test
    fun openingTheOverlayStartsNoCountdown() {
        assertEquals(0, CompactOverlay.hidden().autoHideToken)
    }

    @Test
    fun theOverlayDefaultsToHiddenWithoutTheFactory() {
        assertEquals(CompactOverlay.hidden(), CompactOverlay())
    }

    // ── A single tap on the cover art ─────────────────────────────────────────

    @Test
    fun aCoverTapShowsTheHiddenOverlay() {
        val shown = CompactOverlay.hidden().reduce(CompactOverlayEvent.COVER_TAP)
        assertTrue(shown.visible)
    }

    @Test
    fun aCoverTapHidesTheVisibleOverlayAtOnce() {
        val hidden = CompactOverlay(visible = true)
            .reduce(CompactOverlayEvent.COVER_TAP)
        assertFalse(hidden.visible)
    }

    @Test
    fun aCoverTapAlwaysArmsTheCountdownAgain() {
        val armedFromHidden = CompactOverlay.hidden().reduce(CompactOverlayEvent.COVER_TAP)
        val armedFromVisible = CompactOverlay(visible = true)
            .reduce(CompactOverlayEvent.COVER_TAP)
        assertEquals(1, armedFromHidden.autoHideToken)
        assertEquals(1, armedFromVisible.autoHideToken)
    }

    @Test
    fun aCoverTapIsAlwaysAToggle() {
        val start = CompactOverlay.hidden()
        val afterOneTap = start.reduce(CompactOverlayEvent.COVER_TAP)
        val afterTwoTaps = afterOneTap.reduce(CompactOverlayEvent.COVER_TAP)
        assertEquals(start.visible, afterTwoTaps.visible)
        assertEquals(start.copy(autoHideToken = 2), afterTwoTaps)
    }

    // ── Auto-hide ─────────────────────────────────────────────────────────────

    @Test
    fun theVisibleOverlayFadesOutOnItsOwn() {
        val faded = CompactOverlay(visible = true)
            .reduce(CompactOverlayEvent.AUTO_HIDE_ELAPSED)
        assertFalse(faded.visible)
    }

    @Test
    fun anElapsedCountdownOnAHiddenOverlayChangesNothing() {
        val hidden = CompactOverlay.hidden()
        assertEquals(hidden, hidden.reduce(CompactOverlayEvent.AUTO_HIDE_ELAPSED))
    }

    @Test
    fun theCountdownWaitsFiveSeconds() {
        assertEquals(5_000L, COMPACT_OVERLAY_AUTO_HIDE_MS)
    }

    @Test
    fun showingTheOverlayStartsTheCountdown() {
        val shown = CompactOverlay.hidden().reduce(CompactOverlayEvent.COVER_TAP)
        assertNotEquals(shown.autoHideToken, CompactOverlay.hidden().autoHideToken)
    }

    // ── Interaction keeps the overlay up ──────────────────────────────────────

    @Test
    fun aControlPressKeepsTheOverlayVisible() {
        val afterPress = CompactOverlay(visible = true)
            .reduce(CompactOverlayEvent.CONTROL_INTERACTION)
        assertTrue(afterPress.visible)
    }

    @Test
    fun aControlPressRestartsTheCountdown() {
        val before = CompactOverlay(visible = true, autoHideToken = 1)
        val after = before.reduce(CompactOverlayEvent.CONTROL_INTERACTION)
        assertEquals(2, after.autoHideToken)
    }

    @Test
    fun everyControlPressRestartsTheCountdownAgain() {
        var overlay = CompactOverlay(visible = true)
        repeat(3) {
            overlay = overlay.reduce(CompactOverlayEvent.CONTROL_INTERACTION)
        }
        assertEquals(3, overlay.autoHideToken)
    }

    @Test
    fun aControlPressOnAHiddenOverlayChangesNothing() {
        val hidden = CompactOverlay.hidden()
        assertEquals(hidden, hidden.reduce(CompactOverlayEvent.CONTROL_INTERACTION))
    }

    @Test
    fun interactingNeverResurrectsAHiddenOverlay() {
        var overlay = CompactOverlay(visible = true)
        repeat(4) {
            overlay = overlay.reduce(CompactOverlayEvent.CONTROL_INTERACTION)
        }
        assertTrue(overlay.visible)
        overlay = overlay.reduce(CompactOverlayEvent.AUTO_HIDE_ELAPSED)
        repeat(4) {
            overlay = overlay.reduce(CompactOverlayEvent.CONTROL_INTERACTION)
        }
        assertFalse("a press after the fade out must not bring the overlay back", overlay.visible)
    }

    // ── Tapping the visible overlay ───────────────────────────────────────────

    @Test
    fun tappingTheVisibleOverlayHidesItRatherThanWaitingOutTheCountdown() {
        val shown = CompactOverlay.hidden().reduce(CompactOverlayEvent.COVER_TAP)
        assertTrue(shown.visible)

        val afterScrimTap = shown.reduce(CompactOverlayEvent.COVER_TAP)
        assertFalse("a tap on the mask must hide the overlay at once", afterScrimTap.visible)
    }

    @Test
    fun theOverlayComesBackOnTheNextTap() {
        val hiddenAgain = CompactOverlay.hidden()
            .reduce(CompactOverlayEvent.COVER_TAP)
            .reduce(CompactOverlayEvent.COVER_TAP)
        assertFalse(hiddenAgain.visible)

        val shownAgain = hiddenAgain.reduce(CompactOverlayEvent.COVER_TAP)
        assertTrue(shownAgain.visible)
    }

    // ── Single versus double tap ──────────────────────────────────────────────

    @Test
    fun aSingleTapTogglesTheOverlay() {
        assertEquals(CoverTapAction.TOGGLE_OVERLAY, coverTapAction(isDoubleTap = false))
    }

    @Test
    fun aDoubleTapOnlyTogglesPlayback() {
        assertEquals(CoverTapAction.TOGGLE_PLAYBACK, coverTapAction(isDoubleTap = true))
    }

    @Test
    fun aDoubleTapNeverTouchesTheOverlay() {
        val shown = CompactOverlay.hidden().reduce(CompactOverlayEvent.COVER_TAP)
        val afterDoubleTap = when (coverTapAction(isDoubleTap = true)) {
            CoverTapAction.TOGGLE_PLAYBACK -> shown
            CoverTapAction.TOGGLE_OVERLAY -> shown.reduce(CompactOverlayEvent.COVER_TAP)
        }
        assertTrue("a double tap must not toggle the overlay", afterDoubleTap.visible)
    }

    @Test
    fun theTapsAreToldApartInsideTheDoubleTapWindow() {
        assertEquals(250L, COMPACT_DOUBLE_TAP_WINDOW_MS)
    }

    @Test
    fun theDoubleTapWindowIsShorterThanTheAutoHideCountdown() {
        assertTrue(COMPACT_DOUBLE_TAP_WINDOW_MS < COMPACT_OVERLAY_AUTO_HIDE_MS)
    }

    // ── The overlay fades rather than snapping ────────────────────────────────

    @Test
    fun theOverlayFadeIsNotInstant() {
        assertTrue(COMPACT_OVERLAY_FADE_MS > 0)
    }

    // ── A whole session on the Compact screen ─────────────────────────────────

    @Test
    fun aSessionOnTheCompactScreenBehavesAsAdvertised() {
        var overlay = CompactOverlay.hidden()
        assertFalse("the screen opens on the bare cover art", overlay.visible)

        overlay = overlay.reduce(CompactOverlayEvent.COVER_TAP)
        assertTrue("one tap brings the controls up", overlay.visible)

        val tokenAfterShowing = overlay.autoHideToken
        overlay = overlay.reduce(CompactOverlayEvent.CONTROL_INTERACTION)
        assertTrue("a play/pause press leaves the controls up", overlay.visible)
        assertEquals(tokenAfterShowing + 1, overlay.autoHideToken)

        overlay = overlay.reduce(CompactOverlayEvent.CONTROL_INTERACTION)
        overlay = overlay.reduce(CompactOverlayEvent.CONTROL_INTERACTION)
        assertTrue(overlay.visible)

        overlay = overlay.reduce(CompactOverlayEvent.AUTO_HIDE_ELAPSED)
        assertFalse("silence fades the controls back out", overlay.visible)

        overlay = overlay.reduce(CompactOverlayEvent.COVER_TAP)
        assertTrue("and a tap brings them straight back", overlay.visible)
    }
}
