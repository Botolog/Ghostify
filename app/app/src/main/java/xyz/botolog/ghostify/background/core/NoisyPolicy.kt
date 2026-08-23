package xyz.botolog.ghostify.background.core

import timber.log.Timber

/**
 * Pure policy for "audio becoming noisy" — i.e. a wired headset unplugging or the
 * A2DP link dropping. The platform broadcasts [ACTION_AUDIO_BECOMING_NOISY] and
 * the media player should pause to avoid leaking audio to the speaker.
 *
 * Kept Android-free (the action string literal mirrors
 * `AudioManager.ACTION_AUDIO_BECOMING_NOISY`) so it is JVM-testable.
 */
object NoisyPolicy {

    /** Intent action broadcast when the audio output becomes noisy. */
    const val ACTION_AUDIO_BECOMING_NOISY: String = "android.media.AUDIO_BECOMING_NOISY"

    /**
     * Determines whether the player should pause for the given intent action.
     *
     * @param intentAction the action from the received broadcast intent.
     * @return true if the player should pause.
     */
    fun shouldPause(intentAction: String?): Boolean {
        Timber.i("NoisyPolicy.shouldPause: START")
        val result = intentAction == ACTION_AUDIO_BECOMING_NOISY
        Timber.i("NoisyPolicy.shouldPause: returning $result")
        return result
    }
}
