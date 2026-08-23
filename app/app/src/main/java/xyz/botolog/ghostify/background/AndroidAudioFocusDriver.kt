package xyz.botolog.ghostify.background

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import xyz.botolog.ghostify.background.core.AudioFocusDriver
import xyz.botolog.ghostify.background.core.AudioFocusLoss
import xyz.botolog.ghostify.background.core.AudioFocusRequestResult
import timber.log.Timber

/**
 * Android `AudioManager`-backed [AudioFocusDriver]. Bridges the framework's
 * integer focus-change codes into the pure [AudioFocusLoss] enum used by the
 * testable [xyz.botolog.ghostify.background.core.AudioFocusController].
 *
 * Uses the modern [AudioFocusRequest] (API 26+) since Ghostify's minSdk is 26.
 *
 * @param context used to obtain the [AudioManager] system service.
 */
class AndroidAudioFocusDriver(context: Context) : AudioFocusDriver {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var listener: AudioFocusDriver.AudioFocusChangeListener? = null

    /**
     * Requests audio focus with [AudioManager.AUDIOFOCUS_GAIN].
     *
     * @param listener callback invoked when the system changes focus.
     * @return [AudioFocusRequestResult.GRANTED] or [AudioFocusRequestResult.DENIED].
     */
    override fun requestFocus(listener: AudioFocusDriver.AudioFocusChangeListener): AudioFocusRequestResult {
        Timber.i("AndroidAudioFocusDriver.requestFocus: START")
        this.listener = listener
        val focusRequest = buildFocusRequest(listener)
        val result = audioManager.requestAudioFocus(focusRequest)
        val decision = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AudioFocusRequestResult.GRANTED
        } else {
            AudioFocusRequestResult.DENIED
        }
        Timber.i("AndroidAudioFocusDriver.requestFocus: returning $decision")
        return decision
    }

    /** Abandons audio focus via the API 26+ path. */
    override fun abandon() {
        Timber.i("AndroidAudioFocusDriver.abandon: START")
        try {
            audioManager.abandonAudioFocusRequest(buildAbandonRequest())
        } catch (t: IllegalArgumentException) {
            Timber.e(t, "AndroidAudioFocusDriver: abandon FAILED")
        }
    }

    /**
     * Builds an [AudioFocusRequest] for requesting focus.
     *
     * @param listener callback for focus-change events.
     */
    private fun buildFocusRequest(listener: AudioFocusDriver.AudioFocusChangeListener): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(buildMediaAudioAttributes())
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(
                { lossCode: Int -> listener.onChange(AudioFocusLoss.fromInt(lossCode)) },
                Handler(Looper.getMainLooper()),
            )
            .build()

    /** Builds a minimal [AudioFocusRequest] used solely for abandoning focus. */
    private fun buildAbandonRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(buildMediaAudioAttributes())
            .build()

    /** Returns [AudioAttributes] configured for media playback. */
    private fun buildMediaAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
}
