package com.ghostify.background

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.ghostify.background.core.AudioFocusDriver
import com.ghostify.background.core.AudioFocusLoss
import com.ghostify.background.core.AudioFocusRequestResult

/**
 * Android `AudioManager`-backed [AudioFocusDriver]. Bridges the framework's
 * integer focus-change codes into the pure [AudioFocusLoss] enum used by the
 * testable [com.ghostify.background.core.AudioFocusController].
 *
 * Uses the modern [AudioFocusRequest] (API 26+) since Ghostify's minSdk is 26.
 */
class AndroidAudioFocusDriver(context: Context) : AudioFocusDriver {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var listener: AudioFocusDriver.AudioFocusChangeListener? = null

    override fun requestFocus(listener: AudioFocusDriver.AudioFocusChangeListener): AudioFocusRequestResult {
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
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AudioFocusRequestResult.GRANTED
        } else {
            AudioFocusRequestResult.DENIED
        }
    }

    override fun abandon() {
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
        } catch (_: IllegalArgumentException) {
            // Focus was never held (e.g. denied) — nothing to release.
        }
    }
}
