package com.ghostify.background.core

/**
 * Pure policy for "audio becoming noisy" — i.e. a wired headset unplugging or the
 * A2DP link dropping. The platform broadcasts [ACTION_AUDIO_BECOMING_NOISY] and
 * the media player should pause to avoid leaking audio to the speaker.
 *
 * Kept Android-free (the action string literal mirrors
 * `AudioManager.ACTION_AUDIO_BECOMING_NOISY`) so it is JVM-testable.
 */
object NoisyPolicy {
    const val ACTION_AUDIO_BECOMING_NOISY: String = "android.media.AUDIO_BECOMING_NOISY"

    fun shouldPause(intentAction: String?): Boolean = intentAction == ACTION_AUDIO_BECOMING_NOISY
}
