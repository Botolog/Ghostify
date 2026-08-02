package com.ghostify.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

/**
 * Process-wide holder of THE single [ExoPlayer] and [MediaSession].
 *
 * Both the in-app UI ([PlayerController]) and the background host
 * ([com.ghostify.background.PlaybackService]) drive the same player/session, so
 * there is never more than one audio engine or lockscreen/notification session
 * (PROJECT.md §6.3). Lazily builds on first access — whichever component (UI or
 * service) is touched first wins; the other references the same instances.
 *
 * Release is deliberately process-scoped: the engine is torn down only when the
 * whole app shuts down, never when a single holder goes away.
 */
object PlaybackEngine {

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    @Volatile
    private var session: MediaSession? = null

    @Volatile
    private var sessionActivity: PendingIntent? = null

    /** Returns THE [ExoPlayer], creating it (with default audio-focus handling) on first use. */
    fun exoPlayer(context: Context): ExoPlayer {
        exoPlayer?.let { return it }
        return synchronized(this) {
            exoPlayer ?: ExoPlayer.Builder(context.applicationContext).build().also { exoPlayer = it }
        }
    }

    /**
     * Returns THE [MediaSession], built on the shared player. [sessionActivityClass]
     * is only used the first time the session is created; later calls are no-ops for
     * the activity because the session already exists.
     */
    fun session(context: Context, sessionActivityClass: Class<*>?): MediaSession {
        session?.let { return it }
        val appContext = context.applicationContext
        if (sessionActivityClass != null && sessionActivity == null) {
            sessionActivity = PendingIntent.getActivity(
                appContext,
                /* requestCode = */ 0,
                Intent(appContext, sessionActivityClass),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return synchronized(this) {
            session ?: MediaSession.Builder(appContext, exoPlayer(context))
                .apply { sessionActivity?.let(::setSessionActivity) }
                .build()
                .also { session = it }
        }
    }

    /** Tear down the engine. Only call when every holder (UI + service) is gone. */
    fun release() {
        synchronized(this) {
            session?.release()
            session = null
            exoPlayer?.release()
            exoPlayer = null
            sessionActivity = null
        }
    }
}
