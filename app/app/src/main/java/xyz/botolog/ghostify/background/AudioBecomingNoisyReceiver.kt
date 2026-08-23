package xyz.botolog.ghostify.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.botolog.ghostify.background.core.NoisyPolicy
import xyz.botolog.ghostify.background.core.PlaybackControl
import timber.log.Timber

/**
 * Pauses playback when the audio output becomes noisy (wired headset unplugged,
 * A2DP drop). Registered for [NoisyPolicy.ACTION_AUDIO_BECOMING_NOISY] (T-098).
 *
 * The pause is delegated to the injected [PlaybackControl] so the receiver itself
 * holds no player state and is trivial to drive in tests (Robolectric delivers
 * the broadcast; a fake control records the pause call).
 *
 * @param control the playback control that will be paused on a noisy event.
 */
class AudioBecomingNoisyReceiver(
    private val control: PlaybackControl,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Timber.i("AudioBecomingNoisyReceiver.onReceive: START")
        if (intent == null) return
        if (NoisyPolicy.shouldPause(intent.action)) {
            control.pause()
        }
    }
}
