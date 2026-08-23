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
 *
 * The shutdown button issues a custom [SessionCommand] with action [ACTION_SHUTDOWN],
 * which [PlaybackService] intercepts to terminate the process.
 *
 * @param context used for notification resources.
 * @param smallIconResId drawable resource for the notification small icon.
 */
class PlaybackNotificationProvider(
    context: Context,
    smallIconResId: Int,
) : DefaultMediaNotificationProvider(context) {

    init {
        setSmallIcon(smallIconResId)
    }

    /**
     * Prepends a Shutdown button to the default media buttons.
     *
     * @param session the active media session.
     * @param commands the commands supported by the session.
     * @param commandButtons the default command buttons provided by the parent.
     * @param showCompact whether the compact notification is being rendered.
     * @return an immutable list of buttons with Shutdown at the front.
     */
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

        /** Custom session command action that triggers a full process shutdown. */
        const val ACTION_SHUTDOWN = "xyz.botolog.ghostify.SHUTDOWN"

        /** Display label shown on the shutdown button. */
        private const val SHUTDOWN_LABEL = "Shutdown"
    }
}
