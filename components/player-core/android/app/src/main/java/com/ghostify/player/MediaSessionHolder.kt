package com.ghostify.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/**
 * Owns the [MediaSession] attached to the player.
 *
 * The session keeps itself in sync with the player (queue, state, metadata) so the UI,
 * lockscreen and (when a [androidx.media3.session.MediaSessionService] is registered by the
 * background-playback component) the media notification all reflect the same reality.
 *
 * @param sessionActivityClass optional activity opened when the media notification is tapped;
 *   pass the app's player screen.
 */
class MediaSessionHolder(
    context: Context,
    player: Player,
    sessionActivityClass: Class<*>?,
) {
    private val sessionActivity: PendingIntent? = sessionActivityClass?.let { activityClass ->
        PendingIntent.getActivity(
            context,
            /* requestCode = */ 0,
            Intent(context, activityClass),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    val session: MediaSession = run {
        val builder = MediaSession.Builder(context, player)
        sessionActivity?.let(builder::setSessionActivity)
        builder.build()
    }

    fun release() {
        session.release()
    }
}
