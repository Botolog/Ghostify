package xyz.botolog.ghostify.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The top-bar button is the only place a running timer is visible, so what it says has to be
 * true in both states: an idle button only offers the editor, a running one says how long is
 * left and that it can be cancelled — and the ring it draws empties as that time is used up.
 */
class SleepTimerButtonTest {

    // ── Remaining time ──────────────────────────────────────────────────────

    @Test
    fun theCountdownIsShownInMinutesAndSeconds() {
        assertEquals("0:00", sleepTimerRemainingText(0L))
        assertEquals("0:01", sleepTimerRemainingText(1_000L))
        assertEquals("0:59", sleepTimerRemainingText(59_000L))
        assertEquals("1:00", sleepTimerRemainingText(60_000L))
        assertEquals("29:59", sleepTimerRemainingText(1_799_000L))
    }

    @Test
    fun aCountdownPastAnHourGainsAnHoursField() {
        assertEquals("1:00:00", sleepTimerRemainingText(3_600_000L))
        assertEquals("12:55:55", sleepTimerRemainingText(46_555_000L))
    }

    @Test
    fun theLastSecondOfATimerIsNeverShownAsZero() {
        assertEquals("0:01", sleepTimerRemainingText(1L))
        assertEquals("0:01", sleepTimerRemainingText(1_000L))
        assertEquals("0:02", sleepTimerRemainingText(1_001L))
    }

    @Test
    fun anOverdueTimerReadsAsOver() {
        assertEquals("0:00", sleepTimerRemainingText(-1L))
        assertEquals(0L, sleepTimerSecondsRemaining(-1L))
    }

    @Test
    fun aRunningTimerNeverCountsPastItsOwnTotal() {
        val total = 600_000L
        val remaining = 600_000L

        assertEquals(remaining / total.toFloat(), sleepTimerRingFraction(true, remaining, total), 0.001f)
    }

    // ── Shape ───────────────────────────────────────────────────────────────

    @Test
    fun theIdleButtonIsASquareBigEnoughForItsHourglass() {
        assertEquals(SLEEP_TIMER_IDLE_SIZE.width, SLEEP_TIMER_IDLE_SIZE.height)
        assertTrue(SLEEP_TIMER_IDLE_ICON_SIZE < SLEEP_TIMER_IDLE_SIZE.width)
    }

    @Test
    fun onlyTheRunningButtonIsFlattened() {
        assertEquals(SLEEP_TIMER_IDLE_SIZE.height, sleepTimerButtonMinHeight(false))
        assertEquals(SLEEP_TIMER_ACTIVE_HEIGHT, sleepTimerButtonMinHeight(true))
    }

    @Test
    fun theRunningButtonIsShorterThanItIsWide() {
        assertTrue(SLEEP_TIMER_ACTIVE_HEIGHT < SLEEP_TIMER_IDLE_SIZE.height)
        assertTrue(SLEEP_TIMER_ACTIVE_HEIGHT > SLEEP_TIMER_IDLE_ICON_SIZE)
    }

    @Test
    fun theRunningButtonOnlyGrowsAsFarAsTheCountdownNeeds() {
        val longest = "HH:MM:SS"
        val countdowns = listOf(0L, 1_000L, 59_000L, 600_000L, 3_600_000L, 359_999_000L)

        countdowns.forEach { remainingMs ->
            val label = sleepTimerRemainingText(remainingMs)
            assertTrue("'$label' is longer than $longest", label.length <= longest.length)
        }
        assertEquals("59:59", sleepTimerRemainingText(3_599_000L))
        assertEquals("1:00:00", sleepTimerRemainingText(3_600_000L))
    }

    // ── The ring ────────────────────────────────────────────────────────────

    @Test
    fun theRingIsWholeAtTheStartAndEmptyAtTheEnd() {
        assertEquals(1f, sleepTimerRingFraction(true, 600_000L, 600_000L), 0f)
        assertEquals(0.5f, sleepTimerRingFraction(true, 300_000L, 600_000L), 0.001f)
        assertEquals(0f, sleepTimerRingFraction(true, 0L, 600_000L), 0f)
    }

    @Test
    fun anIdleOrUnmeasuredTimerDrawsNoRing() {
        assertEquals(0f, sleepTimerRingFraction(false, 300_000L, 600_000L), 0f)
        assertEquals(0f, sleepTimerRingFraction(true, 300_000L, 0L), 0f)
    }

    @Test
    fun theRingOnlyEverEmptiesAsTheTimeIsUsedUp() {
        val total = 600_000L
        var previous = 1f

        var used = 0L
        while (used <= total) {
            val fraction = sleepTimerRingFraction(true, total - used, total)
            assertTrue("ring grew at $used ms", fraction <= previous)
            previous = fraction
            used += 5_000L
        }
        assertEquals(0f, previous, 0f)
    }

    // ── Accessibility ───────────────────────────────────────────────────────

    @Test
    fun theIdleButtonOnlyOffersTheEditor() {
        assertEquals(SLEEP_TIMER_IDLE_DESCRIPTION, sleepTimerButtonDescription(false, 0L))
    }

    @Test
    fun theRunningButtonSaysHowLongIsLeftAndHowToCancel() {
        val description = sleepTimerButtonDescription(true, 125_000L)

        assertTrue(description.contains("2 minutes 5 seconds"))
        assertTrue(description.contains("cancel"))
        assertFalse(description == SLEEP_TIMER_IDLE_DESCRIPTION)
    }

    @Test
    fun theSpokenCountdownUsesTheLargestUnitThatStillSaysSomething() {
        assertEquals("1 hour 5 minutes 30 seconds", sleepTimerSpokenRemaining(3_930_000L))
        assertEquals("30 seconds", sleepTimerSpokenRemaining(30_000L))
        assertEquals("5 minutes", sleepTimerSpokenRemaining(300_000L))
        assertEquals("0 seconds", sleepTimerSpokenRemaining(0L))
    }

    @Test
    fun theTestTagsAreDistinct() {
        val tags = listOf(
            SleepTimerTestTags.BUTTON,
            SleepTimerTestTags.DIALOG,
            SleepTimerTestTags.CLOCK,
            SleepTimerTestTags.HOURS_INCREMENT,
            SleepTimerTestTags.HOURS_DECREMENT,
            SleepTimerTestTags.MINUTES_INCREMENT,
            SleepTimerTestTags.MINUTES_DECREMENT,
            SleepTimerTestTags.SECONDS_INCREMENT,
            SleepTimerTestTags.SECONDS_DECREMENT,
            SleepTimerTestTags.START,
            SleepTimerTestTags.CANCEL,
        )

        assertEquals(tags.size, tags.toSet().size)
        assertTrue(tags.none { it.isBlank() })
    }
}
