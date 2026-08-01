package com.ghostify.background

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-103 (Instrumentation, device-only). Verifies that the `MediaSession` exposes a
 * playback state (state, playing, position, speed) that matches the underlying
 * ExoPlayer, as seen by a connected `MediaController`.
 *
 * Compile-checked in the headless build (androidCheckTest); executes on an
 * emulator/device via `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceMediaSessionTest {

    private lateinit var context: Context
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var controller: MediaController? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        controller?.release()
        session?.release()
        player?.release()
    }

    @Test
    fun t103_mediaSession_state_matches_player() {
        player = ExoPlayer.Builder(context).build()
        player!!.setMediaItem(MediaItem.fromUri(Uri.fromFile(writeWav("t103.wav"))))
        player!!.prepare()
        session = MediaSession.Builder(context, player!!).build()

        val future: ListenableFuture<MediaController> =
            MediaController.Builder(context, session!!.token).buildAsync()
        controller = future.get(5, TimeUnit.SECONDS)
        assertTrue(controller!!.isConnected)

        controller!!.play()

        // Wait until the player is actually playing.
        while (!player!!.isPlaying) {
            Thread.sleep(50)
        }

        // State + playing must agree.
        assertEquals(player!!.playbackState, controller!!.playbackState)
        assertEquals(player!!.isPlaying, controller!!.isPlaying)

        // Position must be within a small tolerance (async controller sync).
        val diff = Math.abs(player!!.currentPosition - controller!!.currentPosition)
        assertTrue("position drift $diff ms too large", diff < 2500)

        // Speed: set on the player; session/controller must reflect it.
        player!!.setPlaybackParameters(PlaybackParameters(1.2f, 1.0f))
        Thread.sleep(500)
        assertEquals(1.2f, controller!!.playbackParameters.speed, 0.01f)

        // Speed set from a controller must reach the player too.
        controller!!.setPlaybackParameters(PlaybackParameters(0.9f, 1.0f))
        Thread.sleep(500)
        assertEquals(0.9f, player!!.playbackParameters.speed, 0.01f)
    }

    private fun writeWav(name: String): File {
        val f = File(context.cacheDir, name)
        val sampleRate = 8000
        val durationMs = 2000
        val numSamples = sampleRate.toLong() * durationMs / 1000L
        val dataSize = (numSamples * 2).toInt()
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        buf.put(ByteArray(dataSize))
        f.writeBytes(buf.array())
        return f
    }
}
