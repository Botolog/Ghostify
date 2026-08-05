package com.ghostify.background

import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ghostify.MainActivity
import com.ghostify.R
import com.ghostify.background.core.AudioFocusController
import com.ghostify.background.core.FocusPolicy
import com.ghostify.player.PlaybackEngine
import timber.log.Timber

/**
 * Foreground `MediaSessionService` that is the single source of truth for audio
 * playback in Ghostify.
 *
 * Responsibilities:
 *  - Hosts the shared `ExoPlayer` + `MediaSession` from [PlaybackEngine]; hands
 *    the session to controllers (UI `MediaController`, Bluetooth, Android Auto)
 *    via [onGetSession]. The UI's [com.ghostify.player.PlayerController] drives
 *    the same player, so there is never a second audio engine.
 *  - Runs as a foreground service via a `MediaNotification.Provider`
 *    ([PlaybackNotificationProvider]) so playback survives the app being swiped
 *    away (T-093, T-096) and works under battery saver (T-106).
 *  - Audio focus is primarily owned by ExoPlayer's automatic handling (the shared
 *    player is built with default focus attributes); the explicit
 *    [AudioFocusController] and [AudioBecomingNoisyReceiver] are kept as a
 *    complementary safety net (T-098, T-099).
 *  - Tears the service down on an explicit Stop / end-of-queue, so there is never
 *    a zombie notification (T-097).
 *  - Survives configuration changes (T-102): the player lives in the service, not
 *    the Activity, so rotation only rebinds a `MediaController`.
 *
 * Media buttons (T-101), notification actions (T-100), lockscreen controls (T-095)
 * and the seek bar all flow through the MediaSession -> player, which the
 * foreground service keeps alive.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val NOTIFICATION_ID: Int = 1
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var focusController: AudioFocusController? = null
    private var noisyReceiver: AudioBecomingNoisyReceiver? = null
    private var playbackEverStarted = false

    override fun onCreate() {
        super.onCreate()
        Timber.i("PlaybackService.onCreate: START")
        try {
            Timber.i("PlaybackService.onCreate: setting notification provider")
            setMediaNotificationProvider(
                PlaybackNotificationProvider(this, R.drawable.ic_notification)
            )
            Timber.i("PlaybackService.onCreate: notification provider set, calling initSessionAndPlayer")
        } catch (t: Throwable) {
            Timber.e(t, "PlaybackService.onCreate: notification provider FAILED")
            throw t
        }
        initSessionAndPlayer()
        Timber.i("PlaybackService.onCreate: DONE")
    }

    /** Hands out the session created eagerly in [onCreate]. */
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession {
        Timber.i("PlaybackService.onGetSession: START, session=${session != null}")
        val s = session ?: throw IllegalStateException("Session not created in onCreate")
        Timber.i("PlaybackService.onGetSession: returning session")
        return s
    }

    private fun initSessionAndPlayer() {
        if (session != null) return
        Timber.i("initSessionAndPlayer: START")
        try {
            Timber.i("initSessionAndPlayer: calling PlaybackEngine.exoPlayer(this)")
            val p = PlaybackEngine.exoPlayer(this)
            Timber.i("initSessionAndPlayer: ExoPlayer obtained, creating PlayerControlAdapter")
            val control = PlayerControlAdapter(p)
            Timber.i("initSessionAndPlayer: PlayerControlAdapter created, creating AudioFocusController")
            val focus = AudioFocusController(AndroidAudioFocusDriver(this), control, FocusPolicy())
            Timber.i("initSessionAndPlayer: AudioFocusController created, setting focusController")
            control.focusController = focus
            Timber.i("initSessionAndPlayer: creating AudioBecomingNoisyReceiver")
            val noisy = AudioBecomingNoisyReceiver(control)
            Timber.i("initSessionAndPlayer: AudioBecomingNoisyReceiver created, adding player listener")

            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playbackEverStarted = true
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_IDLE && playbackEverStarted) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        focusController?.abandon()
                        stopSelf()
                    }
                }
            })

            Timber.i("initSessionAndPlayer: calling PlaybackEngine.session(this, MainActivity)")
            session = PlaybackEngine.session(this, MainActivity::class.java)
            Timber.i("initSessionAndPlayer: session created, assigning fields")
            player = p
            focusController = focus
            noisyReceiver = noisy
            Timber.i("initSessionAndPlayer: registering noisy receiver")
            registerReceiver(noisy, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            playbackEverStarted = false
            Timber.i("initSessionAndPlayer: DONE - session=${session != null}, player=${player != null}")
        } catch (t: Throwable) {
            Timber.e(t, "initSessionAndPlayer: CRASHED at step")
            throw t
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("PlaybackService.onStartCommand: START, intent=$intent, flags=$flags, startId=$startId")
        val result = super.onStartCommand(intent, flags, startId)
        Timber.i("PlaybackService.onStartCommand: returning $result")
        return result
    }

    /**
     * A foreground media service must keep playing after the Recents swipe
     * removes the task (T-096). The default [MediaSessionService.onTaskRemoved]
     * calls `stopSelf()`, which we intentionally do NOT: the foreground
     * notification and foreground state already survive task removal.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("PlaybackService.onTaskRemoved: START")
        // Intentionally empty — keep the foreground service alive.
    }

    override fun onDestroy() {
        Timber.i("PlaybackService.onDestroy: START")
        noisyReceiver?.let { unregisterReceiver(it) }
        focusController?.abandon()
        super.onDestroy()
    }
}
