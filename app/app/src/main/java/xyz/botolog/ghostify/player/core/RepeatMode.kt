package xyz.botolog.ghostify.player.core

/**
 * Repeat modes for the player.
 *
 * [media3Value] mirrors `androidx.media3.common.Player.REPEAT_MODE_*` (OFF=0, ONE=1, ALL=2).
 * Keeping the mapping here keeps the core package Android-free and unit-testable; the Android
 * glue passes [media3Value] straight to `ExoPlayer.setRepeatMode`.
 *
 * @property media3Value The integer value matching `Player.REPEAT_MODE_*` constants.
 */
enum class RepeatMode(val media3Value: Int) {
    /** Repeat is disabled; playback stops at the end of the queue. Mirrors `REPEAT_MODE_OFF` (0). */
    OFF(0),

    /** Repeat the current item indefinitely. Mirrors `REPEAT_MODE_ONE` (1). */
    ONE(1),

    /** Repeat the entire queue indefinitely. Mirrors `REPEAT_MODE_ALL` (2). */
    ALL(2);

    /**
     * Returns the next repeat mode in the conventional player control cycle.
     *
     * The cycle order is: OFF → ALL → ONE → OFF.
     *
     * @return The next [RepeatMode] in the cycle.
     */
    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        /**
         * Safely converts a raw Media3 repeat mode integer to a [RepeatMode].
         *
         * @param value The integer value from `Player.repeatMode`.
         * @return The matching [RepeatMode], or [OFF] if the value is unknown.
         */
        fun fromMedia3(value: Int): RepeatMode = when (value) {
            ONE.media3Value -> ONE
            ALL.media3Value -> ALL
            else -> OFF
        }
    }
}
