package com.ghostify.player.core

/**
 * Playback status, mirroring `androidx.media3.common.Player.STATE_*` (IDLE=1, BUFFERING=2,
 * READY=3, ENDED=4).
 */
enum class PlaybackStatus(val playerValue: Int) {
    IDLE(1),
    BUFFERING(2),
    READY(3),
    ENDED(4);

    companion object {
        fun fromPlayerState(state: Int): PlaybackStatus =
            entries.firstOrNull { it.playerValue == state } ?: IDLE
    }
}
