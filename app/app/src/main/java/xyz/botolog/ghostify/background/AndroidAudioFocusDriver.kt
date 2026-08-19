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
 */
class AndroidAudioFocusDriver(context: Context) : AudioFocusDriver {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var listener: AudioFocusDriver.AudioFocusChangeListener? = null

    override fun requestFocus(listener: AudioFocusDriver.AudioFocusChangeListener): AudioFocusRequestResult {
        Timber.i("AndroidAudioFocusDriver.requestFocus: START")
        this.listener = listener
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(
                { lossCode: Int -> listener.onChange(AudioFocusLoss.fromInt(lossCode)) },
                Handler(Looper.getMainLooper()),
            )
            .build()
        val result = audioManager.requestAudioFocus(focusRequest)
        val decision = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AudioFocusRequestResult.GRANTED
        } else {
            AudioFocusRequestResult.DENIED
        }
        Timber.i("AndroidAudioFocusDriver.requestFocus: returning $decision")
        return decision
    }

    override fun abandon() {
        Timber.i("AndroidAudioFocusDriver.abandon: START")
        // Re-request a throwaway focus request to register then abandon via the API 26
        // path; abandonAudioFocusRequest is the matching counterpart.
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .build()
        try {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } catch (t: IllegalArgumentException) {
            Timber.e(t, "AndroidAudioFocusDriver: abandon FAILED")
        }
    }
}
