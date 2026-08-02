package com.ghostify.background

import android.content.Intent
import android.content.IntentFilter
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ghostify.MainActivity
import com.ghostify.R
import com.ghostify.background.core.AudioFocusController
import com.ghostify.background.core.FocusPolicy
import com.ghostify.player.PlaybackEngine

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
        // Set before any session is added so every session gets the same
        // foreground notification (with the Stop button).
        setMediaNotificationProvider(
            PlaybackNotificationProvider(this, R.drawable.ic_notification)
        )
    }

    /** Hands out the shared process-wide session, wiring focus policy around it. */
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession {
        var s = session
        if (s == null) {
            val p = PlaybackEngine.exoPlayer(this)
            val control = PlayerControlAdapter(p)
            val focus = AudioFocusController(AndroidAudioFocusDriver(this), control, FocusPolicy())
            control.focusController = focus
            val noisy = AudioBecomingNoisyReceiver(control)

            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playbackEverStarted = true
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // IDLE reached after we were playing == user pressed Stop or the
                    // queue finished. Media3 dismisses the notification on idle; we
                    // additionally stop the foreground service so it can't linger (T-097).
                    if (state == Player.STATE_IDLE && playbackEverStarted) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        focusController?.abandon()
                        stopSelf()
                    }
                }
            })

            s = PlaybackEngine.session(this, MainActivity::class.java)

            session = s
            player = p
            focusController = focus
            noisyReceiver = noisy
            registerReceiver(noisy, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            playbackEverStarted = false
        }
        return s
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super.onStartCommand(intent, flags, startId)

    /**
     * A foreground media service must keep playing after the Recents swipe
     * removes the task (T-096). The default [MediaSessionService.onTaskRemoved]
     * calls `stopSelf()`, which we intentionally do NOT: the foreground
     * notification and foreground state already survive task removal.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally empty — keep the foreground service alive.
    }

    override fun onDestroy() {
        noisyReceiver?.let { unregisterReceiver(it) }
        focusController?.abandon()
        // The shared player/session are owned by PlaybackEngine and must NOT be
        // released here; the UI controller may still be using them.
        super.onDestroy()
    }
}
