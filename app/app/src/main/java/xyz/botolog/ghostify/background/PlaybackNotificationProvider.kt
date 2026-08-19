package xyz.botolog.ghostify.background

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import timber.log.Timber

/**
 * Default Media3 notification provider with a **Shutdown** button on the left (kills
 * the app) and the standard previous/play-pause/next buttons.
 */
class PlaybackNotificationProvider(
    context: Context,
    smallIconResId: Int,
) : DefaultMediaNotificationProvider(context) {

    init {
        setSmallIcon(smallIconResId)
    }

    override fun getMediaButtons(
        session: MediaSession,
        commands: Player.Commands,
        commandButtons: com.google.common.collect.ImmutableList<CommandButton>,
        showCompact: Boolean,
    ): com.google.common.collect.ImmutableList<CommandButton> {
        Timber.i("PlaybackNotificationProvider.getMediaButtons: START")
        val defaults = super.getMediaButtons(session, commands, commandButtons, showCompact)
        val shutdownButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setSessionCommand(SessionCommand(ACTION_SHUTDOWN, android.os.Bundle()))
            .setDisplayName(SHUTDOWN_LABEL)
            .build()
        val result = com.google.common.collect.ImmutableList.builder<CommandButton>()
            .add(shutdownButton)
            .addAll(defaults)
            .build()
        Timber.i("PlaybackNotificationProvider.getMediaButtons: returning ${result.size} buttons")
        return result
    }

    companion object {
        const val ACTION_SHUTDOWN = "xyz.botolog.ghostify.SHUTDOWN"
        private const val SHUTDOWN_LABEL = "Shutdown"
    }
}
