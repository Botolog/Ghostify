package com.ghostify.background

import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.ghostify.background.core.PlaybackControl
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-098 (Robolectric). Verifies the wired `AudioBecomingNoisyReceiver` actually
 * pauses playback when the platform broadcasts that audio is becoming noisy
 * (headset unplugged / A2DP drop), using a fake [PlaybackControl].
 *
 * On-device this is a manual check (T-098); here we assert the glue contracts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AudioBecomingNoisyReceiverTest {

    @Test
    fun `noisy intent pauses playback`() {
        var paused = false
        val control = object : PlaybackControl {
            override val isPlaying: Boolean = false
            override val currentPositionMs: Long = 0L
            override val durationMs: Long = 0L
            override val speed: Float = 1f
            override val isLoading: Boolean = false
            override fun play() {}
            override fun pause() { paused = true }
            override fun stop() {}
            override fun setDucking(duck: Boolean) {}
        }

        val receiver = AudioBecomingNoisyReceiver(control)
        val intent = Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)

        assertTrue("player should have been paused on becoming-noisy", paused)
    }

    @Test
    fun `unrelated intent does not pause`() {
        var paused = false
        val control = object : PlaybackControl {
            override val isPlaying: Boolean = false
            override val currentPositionMs: Long = 0L
            override val durationMs: Long = 0L
            override val speed: Float = 1f
            override val isLoading: Boolean = false
            override fun play() {}
            override fun pause() { paused = true }
            override fun stop() {}
            override fun setDucking(duck: Boolean) {}
        }

        AudioBecomingNoisyReceiver(control).onReceive(
            ApplicationProvider.getApplicationContext(),
            Intent("com.example.UNRELATED"),
        )
        assertTrue(!paused)
    }
}
