package xyz.botolog.ghostify.player.core

/**
 * Playback status, mirroring `androidx.media3.common.Player.STATE_*`.
 *
 * Each entry maps to the corresponding integer constant used by the Media3 player.
 * Keeping the mapping here keeps the core package Android-free and unit-testable.
 *
 * @property playerValue The integer value matching `Player.STATE_*` constants.
 */
enum class PlaybackStatus(val playerValue: Int) {
    /** No media is loaded, or the player has been released. Mirrors `STATE_IDLE` (1). */
    IDLE(1),

    /** The player is buffering data and cannot yet play. Mirrors `STATE_BUFFERING` (2). */
    BUFFERING(2),

    /** The player has enough data to begin or resume playback. Mirrors `STATE_READY` (3). */
    READY(3),

    /** Playback has reached the end of the media. Mirrors `STATE_ENDED` (4). */
    ENDED(4);

    companion object {
        /**
         * Safely converts a raw player state integer to a [PlaybackStatus].
         *
         * @param state The integer state from `Player.playbackState`.
         * @return The matching [PlaybackStatus], or [IDLE] if the value is unknown.
         */
        fun fromPlayerState(state: Int): PlaybackStatus =
            entries.firstOrNull { it.playerValue == state } ?: IDLE
    }
}
