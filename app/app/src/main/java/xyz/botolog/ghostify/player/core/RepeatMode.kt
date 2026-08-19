package xyz.botolog.ghostify.player.core

/**
 * Repeat modes for the player.
 *
 * [media3Value] mirrors `androidx.media3.common.Player.REPEAT_MODE_*` (OFF=0, ONE=1, ALL=2).
 * Keeping the mapping here keeps the core package Android-free and unit-testable; the Android
 * glue passes [media3Value] straight to `ExoPlayer.setRepeatMode`.
 */
enum class RepeatMode(val media3Value: Int) {
    OFF(0),
    ONE(1),
    ALL(2);

    /** Cycles OFF -> ALL -> ONE -> OFF (the conventional player control order). */
    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        fun fromMedia3(value: Int): RepeatMode = when (value) {
            ONE.media3Value -> ONE
            ALL.media3Value -> ALL
            else -> OFF
        }
    }
}
