package xyz.botolog.ghostify.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep-timer editor is a clock, not a free-text field: every control moves its own unit
 * in fixed steps and stops at its own bound instead of running away, nothing under a second
 * can be started, and the duration handed to the player is the one on the digits.
 */
class SleepTimerDraftTest {

    // ── Defaults ────────────────────────────────────────────────────────────

    @Test
    fun theEditorOpensOnHalfAnHour() {
        assertEquals(0, DEFAULT_SLEEP_TIMER_DRAFT.hours)
        assertEquals(30, DEFAULT_SLEEP_TIMER_DRAFT.minutes)
        assertEquals(0, DEFAULT_SLEEP_TIMER_DRAFT.seconds)
        assertTrue(DEFAULT_SLEEP_TIMER_DRAFT.isValid)
    }

    @Test
    fun anUntouchedDefaultCountsDownForThirtyMinutes() {
        assertEquals(1_800, DEFAULT_SLEEP_TIMER_DRAFT.totalSeconds)
        assertEquals(1_800_000L, DEFAULT_SLEEP_TIMER_DRAFT.totalMs)
    }

    // ── Display ─────────────────────────────────────────────────────────────

    @Test
    fun theClockAlwaysShowsTwoDigitsPerUnit() {
        assertEquals("00:00:00", SleepTimerDraft().display)
        assertEquals("00:30:00", DEFAULT_SLEEP_TIMER_DRAFT.display)
        assertEquals("01:05:55", SleepTimerDraft(hours = 1, minutes = 5, seconds = 55).display)
        assertEquals("12:55:55", SleepTimerDraft(hours = 12, minutes = 55, seconds = 55).display)
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    fun aTimerOfAtLeastOneSecondCanBeStarted() {
        assertTrue(SleepTimerDraft(seconds = 5).isValid)
        assertFalse(SleepTimerDraft().isValid)
        assertEquals(0L, SleepTimerDraft().totalMs)
    }

    @Test
    fun oneSecondIsTheShortestTimerThatCanBeStarted() {
        val draft = SleepTimerDraft().adjust(SleepTimerUnit.SECONDS, 1)

        assertEquals(5, draft.seconds)
        assertTrue(draft.isValid)
    }

    // ── Stepping ────────────────────────────────────────────────────────────

    @Test
    fun eachUnitMovesInItsOwnStep() {
        val draft = SleepTimerDraft()

        assertEquals(1, draft.adjust(SleepTimerUnit.HOURS, 1).hours)
        assertEquals(5, draft.adjust(SleepTimerUnit.MINUTES, 1).minutes)
        assertEquals(5, draft.adjust(SleepTimerUnit.SECONDS, 1).seconds)
        assertEquals(15, draft.adjust(SleepTimerUnit.MINUTES, 3).minutes)
    }

    @Test
    fun aUnitCanAlsoBeMovedDown() {
        val draft = SleepTimerDraft(hours = 2, minutes = 30, seconds = 15)

        assertEquals(1, draft.adjust(SleepTimerUnit.HOURS, -1).hours)
        assertEquals(25, draft.adjust(SleepTimerUnit.MINUTES, -1).minutes)
        assertEquals(10, draft.adjust(SleepTimerUnit.SECONDS, -1).seconds)
        assertEquals(0, draft.adjust(SleepTimerUnit.HOURS, -5).hours)
    }

    @Test
    fun aUnitStopsAtZeroInsteadOfWrappingAround() {
        val draft = SleepTimerDraft(minutes = 5)

        assertEquals(0, draft.adjust(SleepTimerUnit.MINUTES, -1).minutes)
        assertEquals(0, draft.adjust(SleepTimerUnit.MINUTES, -99).minutes)
        assertEquals(0, draft.adjust(SleepTimerUnit.HOURS, -1).hours)
        assertEquals(0, draft.adjust(SleepTimerUnit.SECONDS, -1).seconds)
    }

    @Test
    fun aUnitStopsAtItsOwnCeilingInsteadOfOverflowing() {
        assertEquals(
            SleepTimerDraft.MAX_HOURS,
            SleepTimerDraft().adjust(SleepTimerUnit.HOURS, 99).hours,
        )
        assertEquals(
            SleepTimerDraft.MAX_MINUTES,
            SleepTimerDraft().adjust(SleepTimerUnit.MINUTES, 99).minutes,
        )
        assertEquals(
            SleepTimerDraft.MAX_SECONDS,
            SleepTimerDraft().adjust(SleepTimerUnit.SECONDS, 99).seconds,
        )
    }

    @Test
    fun steppingOneUnitLeavesTheOthersAlone() {
        val draft = SleepTimerDraft(hours = 1, minutes = 30, seconds = 10)

        assertEquals(
            SleepTimerDraft(hours = 1, minutes = 35, seconds = 10),
            draft.adjust(SleepTimerUnit.MINUTES, 1),
        )
    }

    @Test
    fun everyReachableDurationStaysInsideTheBounds() {
        var draft = SleepTimerDraft()
        repeat(200) {
            draft = draft.adjust(SleepTimerUnit.HOURS, 1)
                .adjust(SleepTimerUnit.MINUTES, 1)
                .adjust(SleepTimerUnit.SECONDS, 1)
        }

        assertTrue(draft.hours in 0..SleepTimerDraft.MAX_HOURS)
        assertTrue(draft.minutes in 0..SleepTimerDraft.MAX_MINUTES)
        assertTrue(draft.seconds in 0..SleepTimerDraft.MAX_SECONDS)
        assertTrue(draft.isValid)
    }

    // ── The duration handed to the player ───────────────────────────────────

    @Test
    fun theHandedOverDurationIsTheOneOnTheClock() {
        val draft = SleepTimerDraft(hours = 1, minutes = 30, seconds = 5)

        assertEquals(5_405, draft.totalSeconds)
        assertEquals(5_405_000L, draft.totalMs)
    }

    @Test
    fun aCountdownOfAHourAndAHalfEndsUpOnTheHourAndAHalf() {
        val draft = SleepTimerDraft()
            .adjust(SleepTimerUnit.HOURS, 1)
            .adjust(SleepTimerUnit.MINUTES, 6)

        assertEquals(5_400_000L, draft.totalMs)
        assertEquals("01:30:00", draft.display)
    }

    // ── Test tags ───────────────────────────────────────────────────────────

    @Test
    fun everyUnitHasItsOwnIncrementAndDecrementTag() {
        val tags = SleepTimerUnit.entries.flatMap { listOf(incrementTag(it), decrementTag(it)) }

        assertEquals(6, tags.size)
        assertEquals(6, tags.toSet().size)
        assertTrue(SleepTimerTestTags.HOURS_INCREMENT in tags)
        assertTrue(SleepTimerTestTags.SECONDS_DECREMENT in tags)
    }
}
