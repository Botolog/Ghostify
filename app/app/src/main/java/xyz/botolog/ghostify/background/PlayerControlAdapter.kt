package xyz.botolog.ghostify.background

import androidx.media3.exoplayer.ExoPlayer
import xyz.botolog.ghostify.background.core.AudioFocusController
import xyz.botolog.ghostify.background.core.PlaybackControl
import xyz.botolog.ghostify.background.core.PlayDecision
import timber.log.Timber

/**
 * Adapts an [ExoPlayer] to the [PlaybackControl] contract the background
 * component's collaborators speak.
 *
 * Two responsibilities beyond "delegate":
 *  - **ducking** is implemented as player volume scaling. The shared player uses
 *    ExoPlayer's automatic focus for the primary path; this adapter's volume
 *    ducking is kept as a complementary safety net.
 *  - **focus lifecycle** is tied to play/stop so a request is always paired
 *    with an abandon (T-099). Play requests focus first; stop abandons it.
 *
 * @param player the shared [ExoPlayer] instance to control.
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

    /**
     * Requests audio focus and starts playback if granted.
     *
     * If focus is denied the player is paused (system policy) and left in that state.
     */
    override fun play() {
        Timber.i("PlayerControlAdapter.play: START")
        if (focusController?.requestFocus() == PlayDecision.GRANTED) {
            player.play()
        } else {
            player.pause()
        }
    }

    /** Pauses playback without releasing audio focus. */
    override fun pause() {
        Timber.i("PlayerControlAdapter.pause: START")
        player.pause()
    }

    /** Stops the player and abandons audio focus. */
    override fun stop() {
        Timber.i("PlayerControlAdapter.stop: START")
        player.stop()
        focusController?.abandon()
    }

    /**
     * Toggles ducking by scaling the player volume.
     *
     * @param duck true to reduce volume, false to restore full volume.
     */
    override fun setDucking(duck: Boolean) {
        Timber.i("PlayerControlAdapter.setDucking: START, duck=$duck")
        player.volume = if (duck) DUCK_VOLUME else FULL_VOLUME
    }

    companion object {

        /** Volume level applied when ducking (15 % of full). */
        private const val DUCK_VOLUME = 0.15f

        /** Full (un-ducked) volume level. */
        private const val FULL_VOLUME = 1.0f
    }
}
