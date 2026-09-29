package xyz.botolog.ghostify.player.core

/**
 * Immutable countdown state of the player's sleep timer.
 *
 * The timer is stored as a deadline on a monotonic clock rather than as a counter that is
 * decremented, so the time that passes while the process is backgrounded, the screen is off
 * or the CPU is suspended is still accounted for: the remaining time is always
 * `deadline - now` and never a value that drifted while nobody was ticking.
 *
 * The inactive timer is the default, and is what the timer returns to the moment it fires
 * or is cancelled.
 *
 * @property totalMs duration the timer was started with, or `0` when inactive.
 * @property deadlineMs monotonic-clock instant the timer expires at, or `0` when inactive.
 */
data class SleepTimerState(
    val totalMs: Long = 0L,
    val deadlineMs: Long = 0L,
) {

    /** `true` while a countdown is running. */
    val isActive: Boolean get() = totalMs > 0L

    /**
     * Time left before the timer fires.
     *
     * @param nowMs current value of the monotonic clock.
     * @return remaining milliseconds, never negative; `0` when inactive.
     */
    fun remainingMs(nowMs: Long): Long =
        if (!isActive) 0L else (deadlineMs - nowMs).coerceAtLeast(0L)

    /**
     * Whether the countdown has run out.
     *
     * @param nowMs current value of the monotonic clock.
     * @return `true` when the timer is active and has reached its deadline.
     */
    fun hasExpired(nowMs: Long): Boolean = isActive && remainingMs(nowMs) <= 0L

    /**
     * Fraction of the original duration that is still left, in the range `0f..1f`.
     *
     * @param nowMs current value of the monotonic clock.
     * @return `1f` at the start of the countdown, `0f` once it is over.
     */
    fun fractionRemaining(nowMs: Long): Float =
        if (!isActive) 0f else (remainingMs(nowMs).toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

    companion object {

        /** Shortest countdown the player accepts: one second. */
        const val MIN_DURATION_MS: Long = 1_000L

        /** The idle timer, with nothing counting down. */
        val Inactive: SleepTimerState = SleepTimerState()

        /**
         * Whether a duration is a usable countdown.
         *
         * @param durationMs requested duration in milliseconds.
         * @return `true` when the timer is at least [MIN_DURATION_MS] long.
         */
        fun isValidDuration(durationMs: Long): Boolean = durationMs >= MIN_DURATION_MS

        /**
         * Starts a countdown, or returns [Inactive] for a duration too short to be useful.
         *
         * @param durationMs requested duration in milliseconds.
         * @param nowMs current value of the monotonic clock.
         * @return a running timer, or [Inactive] when [durationMs] is too small.
         */
        fun start(durationMs: Long, nowMs: Long): SleepTimerState =
            if (!isValidDuration(durationMs)) Inactive
            else SleepTimerState(totalMs = durationMs, deadlineMs = nowMs + durationMs)
    }
}
