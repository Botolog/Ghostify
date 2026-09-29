package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep timer counts down on a deadline, not on a counter: the time that passes while
 * nothing is ticking — a backgrounded app, a dark screen, a suspended CPU — is still time
 * the timer has to burn off, and an inactive timer is a real state rather than a zero.
 */
class SleepTimerStateTest {

    @Test
    fun anUnsetTimerIsInactive() {
        val timer = SleepTimerState()

        assertFalse(timer.isActive)
        assertEquals(0L, timer.totalMs)
        assertEquals(0L, timer.remainingMs(NOW))
        assertEquals(0f, timer.fractionRemaining(NOW), 0f)
        assertFalse(timer.hasExpired(NOW))
    }

    @Test
    fun aFreshTimerStillHasItsWholeDuration() {
        val timer = SleepTimerState.start(TEN_MINUTES_MS, NOW)

        assertTrue(timer.isActive)
        assertEquals(TEN_MINUTES_MS, timer.totalMs)
        assertEquals(TEN_MINUTES_MS, timer.remainingMs(NOW))
        assertEquals(1f, timer.fractionRemaining(NOW), 0f)
    }

    @Test
    fun theRemainingTimeIsTheTimeLeftUntilTheDeadline() {
        val timer = SleepTimerState.start(TEN_MINUTES_MS, NOW)

        assertEquals(600_000L, timer.remainingMs(NOW))
        assertEquals(599_500L, timer.remainingMs(NOW + 500L))
        assertEquals(300_000L, timer.remainingMs(NOW + 300_000L))
        assertEquals(1_000L, timer.remainingMs(NOW + 599_000L))
    }

    @Test
    fun theTimerOnlyExpiresOnceItReachesItsDeadline() {
        val timer = SleepTimerState.start(TEN_MINUTES_MS, NOW)

        assertFalse(timer.hasExpired(NOW))
        assertFalse(timer.hasExpired(NOW + 599_999L))
        assertTrue(timer.hasExpired(NOW + 600_000L))
        assertTrue(timer.hasExpired(NOW + 900_000L))
    }

    @Test
    fun anOverdueTimerCountsNoNegativeTime() {
        val timer = SleepTimerState.start(TEN_MINUTES_MS, NOW)

        assertEquals(0L, timer.remainingMs(NOW + TEN_MINUTES_MS + 60_000L))
        assertEquals(0f, timer.fractionRemaining(NOW + TEN_MINUTES_MS + 60_000L), 0f)
    }

    @Test
    fun theRemainingFractionHalvesAsTheTimeGoes() {
        val timer = SleepTimerState.start(TEN_MINUTES_MS, NOW)

        assertEquals(0.75f, timer.fractionRemaining(NOW + 150_000L), 0.001f)
        assertEquals(0.5f, timer.fractionRemaining(NOW + 300_000L), 0.001f)
        assertEquals(0.25f, timer.fractionRemaining(NOW + 450_000L), 0.001f)
    }

    @Test
    fun aDurationOfLessThanASecondIsNeverStarted() {
        assertFalse(SleepTimerState.isValidDuration(0L))
        assertFalse(SleepTimerState.isValidDuration(999L))
        assertTrue(SleepTimerState.isValidDuration(1_000L))

        assertEquals(SleepTimerState.Inactive, SleepTimerState.start(0L, NOW))
        assertEquals(SleepTimerState.Inactive, SleepTimerState.start(999L, NOW))
        assertEquals(SleepTimerState.Inactive, SleepTimerState.start(-5_000L, NOW))
    }

    @Test
    fun aRejectedDurationLeavesNothingRunning() {
        val rejected = SleepTimerState.start(-1L, NOW)

        assertFalse(rejected.isActive)
        assertFalse(rejected.hasExpired(NOW))
        assertEquals(0L, rejected.remainingMs(NOW + 1_000_000L))
    }

    @Test
    fun theTimerIsOnlyEqualToAnIdenticalCountdown() {
        val started = SleepTimerState.start(TEN_MINUTES_MS, NOW)

        assertEquals(started, SleepTimerState.start(TEN_MINUTES_MS, NOW))
        assertEquals(started.hashCode(), SleepTimerState.start(TEN_MINUTES_MS, NOW).hashCode())
        assertFalse(started == SleepTimerState.Inactive)
    }

    @Test
    fun aLongTimerDoesNotOverflowItsDeadline() {
        val twelveHours = 12L * 60L * 60L * 1_000L
        val timer = SleepTimerState.start(twelveHours, Long.MAX_VALUE / 4)

        assertTrue(timer.isActive)
        assertTrue(timer.remainingMs(Long.MAX_VALUE / 4) > 0L)
        assertEquals(twelveHours, timer.totalMs)
    }

    private companion object {
        const val NOW = 1_000_000L
        const val TEN_MINUTES_MS = 600_000L
    }
}
