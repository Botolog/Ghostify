package com.ghostify.background

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.ghostify.background.core.AudioFocusController
import com.ghostify.background.core.PlaybackControl
import com.ghostify.background.core.PlayDecision

/**
 * Adapts an [ExoPlayer] to the [PlaybackControl] contract the background
 * component's collaborators speak.
 *
 * Two responsibilities beyond "delegate":
 *  - **ducking** is implemented as player volume scaling (the player manages
 *    volume with [handleAudioFocus=false] on the player, since focus is owned
 *    by [AudioFocusController]).
 *  - **focus lifecycle** is tied to play/stop so a request is always paired
 *    with an abandon (T-099). Play requests focus first; stop abandons it.
 */
class PlayerControlAdapter(
    private val player: ExoPlayer,
) : PlaybackControl {

    /** Set by the service after both objects are constructed (circular dependency). */
    var focusController: AudioFocusController? = null
        set

    override val isPlaying: Boolean get() = player.isPlaying
    override val currentPositionMs: Long get() = player.currentPosition
    override val durationMs: Long get() = player.duration
    override val speed: Float get() = player.playbackParameters.speed
    override val isLoading: Boolean get() = player.isLoading

    override fun play() {
        if (focusController?.requestFocus() == PlayDecision.GRANTED) {
            player.play()
        } else {
            // Focus denied: do not play (system policy), leave paused.
            player.pause()
        }
    }

    override fun pause() = player.pause()

    override fun stop() {
        player.stop()
        focusController?.abandon()
    }

    override fun setDucking(duck: Boolean) {
        player.volume = if (duck) DUCK_VOLUME else FULL_VOLUME
    }

    companion object {
        private const val DUCK_VOLUME = 0.15f
        private const val FULL_VOLUME = 1.0f
    }
}

/** Builds an ExoPlayer configured for media-playback focus + attribute policy. */
internal fun buildExoPlayer(context: android.content.Context): ExoPlayer = ExoPlayer.Builder(context).build().apply {
    setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .build(),
        /* handleAudioFocus = */ false, // owned by AudioFocusController
    )
    // Foreground-service playback already keeps the process alive across screen
    // off (T-093), so no WAKE_LOCK / setWakeMode is needed here.
}
