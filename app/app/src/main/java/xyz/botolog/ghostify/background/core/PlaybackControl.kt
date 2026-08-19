package xyz.botolog.ghostify.background.core

/**
 * Minimal playback surface the background component drives.
 *
 * Deliberately decoupled from ExoPlayer/MediaSession so the audio-focus and
 * becoming-noisy policy can be unit-tested with a trivial fake instead of a real
 * player (which needs an audio endpoint and therefore a device).
 */
interface PlaybackControl {
    val isPlaying: Boolean
    val currentPositionMs: Long
    val durationMs: Long
    val speed: Float
    val isLoading: Boolean
    fun play()
    fun pause()
    fun stop()
    fun setDucking(duck: Boolean)
}
