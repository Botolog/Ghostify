package xyz.botolog.ghostify.background.core

/**
 * Minimal playback surface the background component drives.
 *
 * Deliberately decoupled from ExoPlayer/MediaSession so the audio-focus and
 * becoming-noisy policy can be unit-tested with a trivial fake instead of a real
 * player (which needs an audio endpoint and therefore a device).
 */
interface PlaybackControl {

    /** Whether the player is currently producing audio. */
    val isPlaying: Boolean

    /** Current playback position in milliseconds. */
    val currentPositionMs: Long

    /** Duration of the current media item in milliseconds, or [Long.MAX_VALUE] if unknown. */
    val durationMs: Long

    /** Current playback speed (1.0 = normal). */
    val speed: Float

    /** Whether the player is currently buffering. */
    val isLoading: Boolean

    /** Starts or resumes playback. */
    fun play()

    /** Pauses playback without releasing resources. */
    fun pause()

    /** Stops playback and releases player resources. */
    fun stop()

    /**
     * Toggles ducking mode (volume reduction for transient focus loss).
     *
     * @param duck true to reduce volume, false to restore full volume.
     */
    fun setDucking(duck: Boolean)
}
